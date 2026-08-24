package com.nexi.recommendations

import com.nexi.moderation.CreateReportRequest
import com.nexi.moderation.InMemoryModerationRepository
import com.nexi.moderation.ModerationService
import com.nexi.moderation.ReportTargetType
import com.nexi.posts.CreatePostRequest
import com.nexi.posts.FakeObjectStorage
import com.nexi.posts.InMemoryPostRepository
import com.nexi.posts.PostService
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Beğeni, kaydetme ve şikâyet sinyalleri backend'in kendi işlemi içinde
 * üretilir. Bunları istemciye bırakmak, ağ kesildiğinde ya da uygulama
 * kapandığında en değerli sinyalleri kaybetmek demekti.
 */
class ServerGeneratedSignalTest {
    private val now = Instant.parse("2026-08-23T12:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val viewerId = UUID.randomUUID()
    private val events = RecordingRecommendationRepository()

    private val postRepository = InMemoryPostRepository(viewerId, now)
    private val posts = PostService(
        repository = postRepository,
        storage = FakeObjectStorage(),
        clock = clock,
        recommendationRepository = events,
    )

    private val moderationRepository = InMemoryModerationRepository(now)
    private val moderation = ModerationService(
        repository = moderationRepository,
        users = { username -> moderationRepository.idByUsername(username) },
        storage = FakeObjectStorage(),
        clock = clock,
        recommendations = { batch -> events.append(batch) },
    )

    private fun publish() = UUID.fromString(posts.create(viewerId, CreatePostRequest("Gönderi")).id)

    @Test
    fun `liking a post writes the signal without the client sending anything`() {
        val postId = publish()

        posts.setLike(viewerId, postId, active = true)

        val signal = events.written.single { it.eventType == RecommendationEventType.CONTENT_LIKED }
        assertEquals(postId, signal.postId)
        assertEquals(viewerId, signal.userId)
        assertEquals(EventPlatform.BACKEND, signal.platform)
        assertEquals(EventContract.CURRENT_VERSION, signal.schemaVersion)
    }

    @Test
    fun `saving writes its own signal`() {
        val postId = publish()

        posts.setSave(viewerId, postId, active = true)

        assertEquals(postId, events.written.single { it.eventType == RecommendationEventType.CONTENT_SAVED }.postId)
    }

    @Test
    fun `only a real transition counts`() {
        val postId = publish()

        // Arayüzün tekrarladığı istek ya da çift dokunuş aynı beğeniyi iki kez
        // öğretmemeli; geri alma da olumlu sinyal üretmemeli.
        posts.setLike(viewerId, postId, active = true)
        posts.setLike(viewerId, postId, active = true)
        posts.setLike(viewerId, postId, active = false)

        assertEquals(1, events.written.count { it.eventType == RecommendationEventType.CONTENT_LIKED })
    }

    @Test
    fun `liking again after undoing is a new signal`() {
        val postId = publish()

        posts.setLike(viewerId, postId, active = true)
        posts.setLike(viewerId, postId, active = false)
        posts.setLike(viewerId, postId, active = true)

        assertEquals(2, events.written.count { it.eventType == RecommendationEventType.CONTENT_LIKED })
    }

    @Test
    fun `reporting a post writes the strongest negative signal once`() {
        val reporterId = moderationRepository.addUser("Gizem Derici", "gizem")
        val postId = moderationRepository.addTarget(ReportTargetType.POST)
        val request = CreateReportRequest(targetType = "POST", targetId = postId.toString(), reason = "SPAM")

        moderation.report(reporterId, request)
        moderation.report(reporterId, request)

        val reported = events.written.filter { it.eventType == RecommendationEventType.CONTENT_REPORTED }
        assertEquals(1, reported.size)
        assertEquals(postId, reported.single().postId)
    }

    @Test
    fun `reporting a user produces no ranking signal`() {
        val reporterId = moderationRepository.addUser("Gizem Derici", "gizem")
        val targetId = moderationRepository.addUser("Mert Arslan", "mert")

        moderation.report(
            reporterId,
            CreateReportRequest(targetType = "USER", targetId = targetId.toString(), reason = "SPAM"),
        )

        assertTrue(events.written.none { it.eventType == RecommendationEventType.CONTENT_REPORTED })
    }
}

private class RecordingRecommendationRepository : RecommendationRepository {
    val written = mutableListOf<RecommendationEvent>()
    override val personalizationAvailable = false

    override fun append(events: List<RecommendationEvent>): Int {
        written += events
        return events.size
    }

    override fun recentSignals(userId: UUID, limit: Int, notAfter: Instant?) = emptyList<RecommendationSignal>()
    override fun profileStats(userId: UUID) = RecommendationProfileStats(written.size.toLong(), null)
    override fun clear(userId: UUID) = written.clear()
    override fun hide(userId: UUID, postId: UUID, now: Instant) = Unit
}
