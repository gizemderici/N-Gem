package com.nexi.feed

import com.nexi.posts.CandidateSource
import com.nexi.posts.CreatePostRequest
import com.nexi.posts.FakeObjectStorage
import com.nexi.posts.FeedCursor
import com.nexi.posts.InMemoryPostRepository
import com.nexi.posts.PostDetails
import com.nexi.posts.PostRepository
import com.nexi.posts.PostService
import com.nexi.recommendations.ContextualRanker
import com.nexi.recommendations.FeedRecommendationContext
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
 * Gölge koşusu, öldürme anahtarı ve hata yedeği — Faz 6'nın kabul kriterleri.
 */
class FeedShadowTest {
    private val now = Instant.parse("2026-08-23T12:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val viewerId = UUID.randomUUID()

    private val topics = InMemoryTopicRepository()
    private val postRepository = InMemoryPostRepository(viewerId, now, topics)
    private val lineage = RecordingLineage()
    private val storage = FakeObjectStorage()
    private val posts = PostService(postRepository, storage, clock)
    private val context = FeedRecommendationContext(12, 180, UUID.randomUUID())

    private fun policy(
        config: FeedExperimentConfig,
        repository: PostRepository = postRepository,
    ) = FeedPolicy(
        posts = repository,
        recommendations = AvailableRepository,
        ranker = ContextualRanker(),
        storage = storage,
        clock = clock,
        lineage = lineage,
        experiments = FeedExperiments(config),
    )

    private fun publish(text: String) = posts.create(viewerId, CreatePostRequest(text = text)).id

    @Test
    fun `the served request is recorded with the arm that actually ranked it`() {
        repeat(3) { publish("Gönderi $it") }

        val page = policy(
            FeedExperimentConfig(weights = mapOf(FeedVariant.HEURISTIC to 1))
        ).feed(viewerId, null, 2, context)

        assertEquals(2, page.items.size)
        val servedId = UUID.fromString(assertNotNull(page.requestId))

        val served = lineage.recorded.single()
        assertEquals(servedId, served.id)
        // Etiket gercekten siralamayi yapan kol olmali; yanlis etiket sonraki
        // kol karsilastirmasini sessizce anlamsizlastirirdi.
        assertEquals(FeedVariant.HEURISTIC.wireName, served.experimentVariant)
        assertEquals(2, served.returnedCount)
        assertNull(served.shadowOf)
    }

    @Test
    fun `no configuration can produce a shadow run today`() {
        // Golge yazma yolu bugun erisilemez ve bunu gizlemek yerine yaziyoruz.
        // CONTROL kolu kisisellestirmeden once donuyor, HEURISTIC ile ayni kol
        // golgelenmiyor, ve LEARNED yapilandirmada reddediliyor. Ikinci bir
        // siralayici geldiginde (Demo Faz 16) yol canlanacak.
        repeat(3) { publish("Gönderi $it") }

        listOf(
            FeedExperimentConfig(weights = mapOf(FeedVariant.HEURISTIC to 1), shadow = FeedVariant.HEURISTIC),
            FeedExperimentConfig(weights = mapOf(FeedVariant.CONTROL to 1), shadow = FeedVariant.HEURISTIC),
        ).forEach { config ->
            lineage.recorded.clear()
            policy(config).feed(viewerId, null, 2, context)
            assertTrue(lineage.recorded.none { it.shadowOf != null }, "golge kaydi beklenmiyor: $config")
        }
    }

    @Test
    fun `no shadow run happens when the arms are the same`() {
        publish("Gönderi")

        policy(
            FeedExperimentConfig(
                weights = mapOf(FeedVariant.HEURISTIC to 1),
                shadow = FeedVariant.HEURISTIC,
            )
        ).feed(viewerId, null, 5, context)

        assertTrue(lineage.recorded.none { it.shadowOf != null })
    }

    @Test
    fun `the kill switch turns the feed chronological with one setting`() {
        repeat(3) { publish("Gönderi $it") }

        val page = policy(
            FeedExperimentConfig(enabled = false, shadow = FeedVariant.HEURISTIC)
        ).feed(viewerId, null, 2, context)

        assertEquals(2, page.items.size)
        assertNull(page.modelVersion, "profil okunmamalı")
        assertNull(page.requestId)
        assertTrue(lineage.recorded.isEmpty())
    }

    @Test
    fun `the control arm gets the chronological feed`() {
        repeat(3) { publish("Gönderi $it") }

        val page = policy(
            FeedExperimentConfig(weights = mapOf(FeedVariant.CONTROL to 1))
        ).feed(viewerId, null, 2, context)

        assertNull(page.modelVersion)
        assertTrue(lineage.recorded.isEmpty())
    }

    @Test
    fun `a ranking failure falls back to the chronological feed`() {
        // Kabul kriteri: model hatasinda otomatik donus. Kullanici bozuk bir
        // model yuzunden bos ekran gormemeli.
        repeat(3) { publish("Gönderi $it") }

        val page = policy(
            FeedExperimentConfig(weights = mapOf(FeedVariant.HEURISTIC to 1)),
            repository = FailingHydrateRepository(postRepository),
        ).feed(viewerId, null, 2, context)

        assertEquals(2, page.items.size)
        assertNull(page.modelVersion, "yedek yol kronolojik")
        assertNotNull(page.nextCursor)
    }

    @Test
    fun `a broken cursor still returns a validation error, not a silent fallback`() {
        // Yedek yol her hatayi yutmamali: gecersiz imlec istemcinin hatasi ve
        // sessizce baska bir sayfa dondurmek onu gizlerdi.
        publish("Gönderi")

        val error = kotlin.runCatching {
            policy(FeedExperimentConfig()).feed(viewerId, "bozuk", 2, context)
        }.exceptionOrNull()

        assertEquals("INVALID_CURSOR", (error as? com.nexi.auth.ApiException)?.code)
    }
}

private class RecordingLineage : FeedLineageRepository {
    val recorded = mutableListOf<FeedRequestRecord>()
    override fun record(request: FeedRequestRecord) {
        recorded += request
    }
}

/** Sıralamanın ortasında patlayan depo; yedek yolu sınamak için. */
private class FailingHydrateRepository(private val delegate: PostRepository) :
    PostRepository by delegate {
    override fun hydrate(details: List<PostDetails>): List<PostDetails> =
        throw IllegalStateException("hidrasyon bozuk")

    override fun feed(viewerId: UUID, cursor: FeedCursor?, limit: Int): List<PostDetails> =
        delegate.feed(viewerId, cursor, limit)

    override fun candidates(
        viewerId: UUID,
        source: CandidateSource,
        priorityTopicCount: Int,
        notAfter: Instant,
        limit: Int,
    ): List<PostDetails> = delegate.candidates(viewerId, source, priorityTopicCount, notAfter, limit)
}

private object AvailableRepository : RecommendationRepository {
    override val personalizationAvailable = true
    override fun append(events: List<RecommendationEvent>) = events.size
    override fun recentSignals(userId: UUID, limit: Int, notAfter: Instant?) = emptyList<RecommendationSignal>()
    override fun profileStats(userId: UUID) = RecommendationProfileStats(0, null)
    override fun clear(userId: UUID) = Unit
    override fun export(userId: UUID) = RecommendationExport()
    override fun deleteOlderThan(cutoff: Instant, batchSize: Int) = 0
    override fun hide(userId: UUID, postId: UUID, now: Instant) = Unit
}
