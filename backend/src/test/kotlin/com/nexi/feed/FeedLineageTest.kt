package com.nexi.feed

import com.nexi.posts.CandidateSource
import com.nexi.posts.CreatePostRequest
import com.nexi.posts.FakeObjectStorage
import com.nexi.posts.InMemoryPostRepository
import com.nexi.posts.PostService
import com.nexi.recommendations.AlwaysGrantedConsentRepository
import com.nexi.recommendations.ContextualRanker
import com.nexi.recommendations.EmptyRecommendationRepository
import com.nexi.recommendations.FeedRecommendationContext
import com.nexi.recommendations.RecommendationConsent
import com.nexi.recommendations.ConsentRepository
import com.nexi.recommendations.RecommendationEvent
import com.nexi.recommendations.RecommendationExport
import com.nexi.recommendations.RecommendationProfileStats
import com.nexi.recommendations.RecommendationRepository
import com.nexi.recommendations.RecommendationSignal
import com.nexi.topics.InMemoryTopicRepository
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Soy kütüğü: bir sıralamanın neden o şekilde oluştuğu sonradan
 * açıklanabilmeli ve eğitim verisi yalnızca istek anında var olan bilgiden
 * üretilebilmeli.
 */
class FeedLineageTest {
    private val now = Instant.parse("2026-08-23T12:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val viewerId = UUID.randomUUID()

    private val topics = InMemoryTopicRepository()
    private val postRepository = InMemoryPostRepository(viewerId, now, topics)
    private val lineage = RecordingLineageRepository()
    private val consents = SwitchableConsentRepository()
    private val storage = FakeObjectStorage()
    private val posts = PostService(postRepository, storage, clock)
    private val ranker = ContextualRanker()
    private val policy = FeedPolicy(
        posts = postRepository,
        recommendations = AvailableRecommendationRepository,
        ranker = ranker,
        storage = storage,
        clock = clock,
        lineage = lineage,
        consents = consents,
    )

    private val context = FeedRecommendationContext(12, 180, UUID.randomUUID())

    private fun publish(text: String = "Gönderi", vararg topicSlugs: String) = UUID.fromString(
        posts.create(
            viewerId,
            CreatePostRequest(text = text, topicIds = topicSlugs.map { topics.topic(it).id.toString() }),
        ).id
    )

    @Test
    fun `the chronological control arm is recorded too`() {
        repeat(3) { publish("Gönderi $it") }
        val control = FeedPolicy(
            posts = postRepository,
            recommendations = AvailableRecommendationRepository,
            ranker = ranker,
            storage = storage,
            clock = clock,
            lineage = lineage,
            consents = consents,
            experiments = FeedExperiments(
                FeedExperimentConfig(weights = mapOf(FeedVariant.CONTROL to 1))
            ),
        )

        control.feed(viewerId, null, 5, context)

        // Kontrol kolu yalnızca `feed_fallbacks`'e yazsaydı kolları
        // karşılaştıran her rapor temel çizgisiz kalırdı.
        val request = lineage.recorded.single()
        assertEquals(FeedVariant.CONTROL.wireName, request.experimentVariant)
        assertEquals(false, request.personalized)
        assertEquals("chronological", request.modelVersion)
        assertEquals(3, request.candidates.size)
        // Kronolojik akışta puan yok; 0.0 yazmak puan dağılımına bakan her
        // sorguyu yanıltırdı.
        assertTrue(request.candidates.all { it.finalScore == null && it.rawScore == null })
        assertEquals(listOf(0, 1, 2), request.candidates.map { it.position })
        assertTrue(request.candidates.all { it.source == CandidateSource.CHRONOLOGICAL })
    }

    // ------------------------------------------------------- aciklanabilirlik

    @Test
    fun `every evaluated candidate is recorded, shown or not`() {
        repeat(5) { publish("Gönderi $it") }

        policy.feed(viewerId, null, 2, context)

        val request = lineage.recorded.single()
        assertEquals(5, request.candidates.size, "elenen adaylar da kayda geçmeli")
        assertEquals(2, request.returnedCount)
        assertEquals(listOf(0, 1), request.candidates.mapNotNull { it.position }.sorted())
        assertEquals(3, request.candidates.count { it.position == null })
    }

    @Test
    fun `the record carries the versions needed to compare two runs`() {
        publish()

        policy.feed(viewerId, null, 5, context)

        val request = lineage.recorded.single()
        assertEquals(ranker.modelVersion, request.modelVersion)
        assertEquals(ranker.featureVersion, request.featureVersion)
        assertEquals(FeedPolicy.POLICY_VERSION, request.policyVersion)
        assertEquals(context.sessionId, request.sessionId)
        assertEquals(12, request.localHour)
        assertTrue(request.personalized)
    }

    @Test
    fun `each candidate keeps its source, both scores and its reason`() {
        publish("Konulu gönderi", "teknoloji")

        policy.feed(viewerId, null, 5, context)

        val candidate = lineage.recorded.single().candidates.single()
        // Yazarın takipçisi yok; NEW_CREATOR önceliği DISCOVERY'den yüksek.
        assertEquals(CandidateSource.NEW_CREATOR, candidate.source)
        assertEquals(listOf("teknoloji"), candidate.topicSlugs)
        assertEquals("text", candidate.mediaType)
        assertTrue((candidate.finalScore ?: 0.0) > 0.0)
        assertNotNull(candidate.reason)
    }

    @Test
    fun `paging records one request per page`() {
        repeat(4) { publish("Gönderi $it") }

        val first = policy.feed(viewerId, null, 2, context)
        policy.feed(viewerId, assertNotNull(first.nextCursor), 2, context)

        assertEquals(2, lineage.recorded.size)
        assertEquals(listOf(0, 1), lineage.recorded[0].candidates.mapNotNull { it.position }.sorted())
        assertEquals(listOf(2, 3), lineage.recorded[1].candidates.mapNotNull { it.position }.sorted())
        // Her sayfa kendi istek kimliğini alır; sunum kaydı buna bağlanır.
        assertEquals(2, lineage.recorded.map { it.id }.distinct().size)
    }

    // ------------------------------------------------- gelecekten bilgi sizmasi

    @Test
    fun `engagement counts are frozen at request time`() {
        // Kabul kriteri: eğitim verisi yalnızca olay anında var olan bilgiyle
        // üretilebilmeli. Sayaçlar sonradan `posts` tablosundan okunsaydı
        // geleceğin beğenileri geçmiş bir isteğe sızardı.
        val postId = publish("Gönderi")

        policy.feed(viewerId, null, 5, context)
        val beforeLikes = lineage.recorded.single().candidates.single().likeCount

        repeat(3) { postRepository.setLike(postId, UUID.randomUUID(), true, now) }

        assertEquals(0, beforeLikes)
        assertEquals(
            0,
            lineage.recorded.single().candidates.single().likeCount,
            "kaydedilmiş sayaç sonradan gelen beğenilerle değişmemeli",
        )
    }

    @Test
    fun `a later request sees the new counts, an earlier one does not`() {
        val postId = publish("Gönderi")
        policy.feed(viewerId, null, 5, context)
        repeat(2) { postRepository.setLike(postId, UUID.randomUUID(), true, now) }

        policy.feed(viewerId, null, 5, context)

        assertEquals(0, lineage.recorded[0].candidates.single().likeCount)
        assertEquals(2, lineage.recorded[1].candidates.single().likeCount)
    }

    @Test
    fun `the profile snapshot only sees signals from before the request`() {
        publish("Gönderi")

        policy.feed(viewerId, null, 5, context)

        // Bellek içi depo sinyal döndürmüyor; anlık görüntü boş olmalı, yani
        // sonradan gelecek sinyaller buraya sızmıyor.
        assertEquals(0, lineage.recorded.single().signalCount)
        assertTrue(lineage.recorded.single().affinities.isEmpty())
    }

    // ------------------------------------------------------------------ riza

    @Test
    fun `without consent the feed is chronological and nothing is recorded`() {
        repeat(3) { publish("Gönderi $it") }
        consents.granted = false

        val page = policy.feed(viewerId, null, 2, context)

        assertEquals(2, page.items.size)
        assertNull(page.modelVersion, "profil okunmamalı")
        assertTrue(lineage.recorded.isEmpty(), "rıza yokken soy kütüğü yazılmamalı")
        assertTrue(page.items.all { it.candidateSource == null })
    }

    @Test
    fun `consent is required even when the request asks for personalization`() {
        publish()
        consents.granted = false

        val page = policy.feed(viewerId, null, 5, context, personalizationEnabled = true)

        assertNull(page.requestId)
        assertTrue(lineage.recorded.isEmpty())
    }
}

private class RecordingLineageRepository : FeedLineageRepository {
    val recorded = mutableListOf<FeedRequestRecord>()
    override fun record(request: FeedRequestRecord) {
        recorded += request
    }
}

private class SwitchableConsentRepository : ConsentRepository {
    var granted = true
    override fun find(userId: UUID) = RecommendationConsent(granted, 1, null)
    override fun set(userId: UUID, granted: Boolean, contractVersion: Int, now: Instant) =
        RecommendationConsent(granted, contractVersion, now).also { this.granted = granted }
}

/** Kişiselleştirmeyi açık gösteren ama sinyal döndürmeyen depo. */
private object AvailableRecommendationRepository : RecommendationRepository {
    override val personalizationAvailable = true
    override fun append(events: List<RecommendationEvent>) = events.size
    override fun recentSignals(userId: UUID, limit: Int, notAfter: Instant?) = emptyList<RecommendationSignal>()
    override fun profileStats(userId: UUID) = RecommendationProfileStats(0, null)
    override fun clear(userId: UUID) = Unit
    override fun export(userId: UUID) = RecommendationExport()
    override fun deleteOlderThan(cutoff: Instant, batchSize: Int) = 0
    override fun hide(userId: UUID, postId: UUID, now: Instant) = Unit
}
