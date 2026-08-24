package com.nexi.feed

import com.nexi.posts.CandidateSource
import com.nexi.posts.CreatePostRequest
import com.nexi.posts.FakeObjectStorage
import com.nexi.posts.FeedCursor
import com.nexi.posts.InMemoryPostRepository
import com.nexi.posts.PostDetails
import com.nexi.posts.PostRepository
import com.nexi.posts.PostService
import com.nexi.recommendations.ConsentRepository
import com.nexi.recommendations.ContextualRanker
import com.nexi.recommendations.FeedRecommendationContext
import com.nexi.recommendations.RecommendationConsent
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
 * Gecikme ve yedeğe düşme kaydı.
 *
 * Yedeğe düşmek sessiz bir olay: kullanıcı kronolojik akış görüyor ve hiçbir
 * şey bozulmuş gibi durmuyor. Kayıt tutulmazsa bozuk bir model ancak kullanıcı
 * şikâyet edince fark edilir.
 */
class FeedHealthTest {
    private val now = Instant.parse("2026-08-23T12:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val viewerId = UUID.randomUUID()

    private val topics = InMemoryTopicRepository()
    private val postRepository = InMemoryPostRepository(viewerId, now, topics)
    private val lineage = CapturingLineage()
    private val fallbacks = CapturingFallbacks()
    private val consents = TogglableConsent()
    private val storage = FakeObjectStorage()
    private val posts = PostService(postRepository, storage, clock)
    private val context = FeedRecommendationContext(12, 180, UUID.randomUUID())

    private fun policy(
        repository: PostRepository = postRepository,
        config: FeedExperimentConfig = FeedExperimentConfig(),
    ) = FeedPolicy(
        posts = repository,
        recommendations = Available,
        ranker = ContextualRanker(),
        storage = storage,
        clock = clock,
        lineage = lineage,
        consents = consents,
        experiments = FeedExperiments(config),
        fallbacks = fallbacks,
    )

    private fun publish(text: String) = posts.create(viewerId, CreatePostRequest(text = text)).id

    @Test
    fun `a served request records how long the ranking took`() {
        repeat(3) { publish("Gönderi $it") }

        policy().feed(viewerId, null, 2, context)

        val duration = assertNotNull(lineage.recorded.single().durationMillis)
        assertTrue(duration >= 0, "süre negatif olamaz")
    }

    @Test
    fun `a ranking failure is recorded as an alarm-worthy fallback`() {
        repeat(2) { publish("Gönderi $it") }

        policy(repository = BrokenRepository(postRepository)).feed(viewerId, null, 2, context)

        val fallback = fallbacks.recorded.single()
        assertEquals(FallbackReason.RANKING_ERROR, fallback.reason)
        assertEquals(viewerId, fallback.userId)
        assertEquals("nexi-contextual-v1", fallback.modelVersion)
        // Yığın izi değil, sınıf adı ve kısa mesaj.
        assertTrue(fallback.detail!!.startsWith("IllegalStateException"))
        assertTrue(fallback.detail!!.length <= 300)
    }

    @Test
    fun `missing consent is recorded separately from a real failure`() {
        // Alarm oranı gürültüye boğulmamalı: rıza yok bir arıza değil.
        publish("Gönderi")
        consents.granted = false

        policy().feed(viewerId, null, 2, context)

        assertEquals(FallbackReason.CONSENT_MISSING, fallbacks.recorded.single().reason)
    }

    @Test
    fun `the kill switch is recorded as a disabled experiment, not an error`() {
        publish("Gönderi")

        policy(config = FeedExperimentConfig(enabled = false)).feed(viewerId, null, 2, context)

        assertEquals(FallbackReason.EXPERIMENT_DISABLED, fallbacks.recorded.single().reason)
    }

    @Test
    fun `an empty candidate pool is recorded`() {
        policy().feed(viewerId, null, 2, context)

        assertEquals(FallbackReason.NO_CANDIDATES, fallbacks.recorded.single().reason)
    }

    @Test
    fun `a client asking for the chronological feed is not an incident`() {
        // `personalized=false` kullanıcının tercihi; alarm sayılmamalı.
        repeat(2) { publish("Gönderi $it") }

        policy().feed(viewerId, null, 2, context, personalizationEnabled = false)

        assertTrue(fallbacks.recorded.isEmpty())
    }

    @Test
    fun `a failing recorder never takes the feed down with it`() {
        // Kayıt patlarsa akışı düşürmenin anlamı yok; zaten yedek yoldayız.
        repeat(2) { publish("Gönderi $it") }
        val exploding = FeedPolicy(
            posts = BrokenRepository(postRepository),
            recommendations = Available,
            ranker = ContextualRanker(),
            storage = storage,
            clock = clock,
            fallbacks = { throw IllegalStateException("kayıt bozuk") },
        )

        val page = exploding.feed(viewerId, null, 2, context)

        assertEquals(2, page.items.size)
        assertNull(page.modelVersion)
    }
}

private class CapturingLineage : FeedLineageRepository {
    val recorded = mutableListOf<FeedRequestRecord>()
    override fun record(request: FeedRequestRecord) {
        recorded += request
    }
}

private class CapturingFallbacks : FeedFallbackRecorder {
    val recorded = mutableListOf<FeedFallback>()
    override fun record(fallback: FeedFallback) {
        recorded += fallback
    }
}

private class TogglableConsent : ConsentRepository {
    var granted = true
    override fun find(userId: UUID) = RecommendationConsent(granted, 1, null)
    override fun set(userId: UUID, granted: Boolean, contractVersion: Int, now: Instant) =
        RecommendationConsent(granted, contractVersion, now)
}

private class BrokenRepository(private val delegate: PostRepository) : PostRepository by delegate {
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

private object Available : RecommendationRepository {
    override val personalizationAvailable = true
    override fun append(events: List<RecommendationEvent>) = events.size
    override fun recentSignals(userId: UUID, limit: Int, notAfter: Instant?) = emptyList<RecommendationSignal>()
    override fun profileStats(userId: UUID) = RecommendationProfileStats(0, null)
    override fun clear(userId: UUID) = Unit
    override fun export(userId: UUID) = RecommendationExport()
    override fun deleteOlderThan(cutoff: Instant, batchSize: Int) = 0
    override fun hide(userId: UUID, postId: UUID, now: Instant) = Unit
}
