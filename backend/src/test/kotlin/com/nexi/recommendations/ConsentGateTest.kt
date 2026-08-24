package com.nexi.recommendations

import com.nexi.moderation.CreateReportRequest
import com.nexi.moderation.InMemoryModerationRepository
import com.nexi.moderation.ModerationService
import com.nexi.moderation.ReportTargetType
import com.nexi.posts.CreatePostRequest
import com.nexi.posts.FakeObjectStorage
import com.nexi.posts.InMemoryPostRepository
import com.nexi.posts.PostService
import com.nexi.topics.InMemoryTopicRepository
import com.nexi.topics.TopicResolver
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Rıza vermemiş kullanıcı hiçbir AI verisi üretmemeli.
 *
 * Kontrol `RecommendationService.append` içinde vardı ve istemciden gelen
 * olayları durduruyordu. Beğeni, kaydetme ve şikâyet sinyalleri AI Faz 1'de
 * sunucu tarafına taşınınca o kapıyı atlar oldular: `PostService` ve
 * `ModerationService` olayı doğrudan depoya yazıyor, rızayı hiç sormuyordu.
 */
class ConsentGateTest {
    private val now = Instant.parse("2026-08-23T12:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val viewerId = UUID.randomUUID()

    private val consent = TogglableConsent()
    private val events = RecordingRepository()
    private val postRepository = InMemoryPostRepository(viewerId, now)
    private val posts = PostService(
        repository = postRepository,
        storage = FakeObjectStorage(),
        clock = clock,
        recommendationRepository = events,
        consent = consent,
    )

    private val moderationRepository = InMemoryModerationRepository(now)
    private val moderation = ModerationService(
        repository = moderationRepository,
        users = { username -> moderationRepository.idByUsername(username) },
        storage = FakeObjectStorage(),
        clock = clock,
        recommendations = { batch -> events.append(batch) },
        consent = consent,
    )

    private fun publish() = UUID.fromString(posts.create(viewerId, CreatePostRequest("Gönderi")).id)

    // ------------------------------------------------------- riza yokken

    @Test
    fun `liking writes no signal without consent`() {
        val postId = publish()
        consent.granted = false

        val response = posts.setLike(viewerId, postId, active = true)

        assertEquals(1, response.count, "beğeninin kendisi yine kaydedilmeli")
        assertTrue(events.written.isEmpty(), "rıza yokken AI sinyali yazılmamalı")
    }

    @Test
    fun `saving writes no signal without consent`() {
        val postId = publish()
        consent.granted = false

        posts.setSave(viewerId, postId, active = true)

        assertTrue(events.written.isEmpty())
    }

    @Test
    fun `reporting writes no signal without consent`() {
        val reporterId = moderationRepository.addUser("Gizem Derici", "gizem")
        val postId = moderationRepository.addTarget(ReportTargetType.POST)
        consent.granted = false

        val report = moderation.report(
            reporterId,
            CreateReportRequest(targetType = "POST", targetId = postId.toString(), reason = "SPAM"),
        )

        // Şikâyetin kendisi moderasyon kaydı; o yazılmaya devam etmeli.
        assertFalse(report.alreadyReported)
        assertEquals(1, moderationRepository.reportCount(reporterId).toInt())
        assertTrue(events.written.isEmpty(), "rıza yokken şikâyet AI sinyali üretmemeli")
    }

    // -------------------------------------------------------- riza varken

    @Test
    fun `the same actions do write signals once consent is given`() {
        // Kapinin kapali olmasi degil, riza durumuna bagli olmasi test ediliyor.
        val postId = publish()
        consent.granted = true

        posts.setLike(viewerId, postId, active = true)
        posts.setSave(viewerId, postId, active = true)

        assertEquals(1, events.written.count { it.eventType == RecommendationEventType.CONTENT_LIKED })
        assertEquals(1, events.written.count { it.eventType == RecommendationEventType.CONTENT_SAVED })
    }

    @Test
    fun `withdrawing consent stops new signals`() {
        val first = publish()
        val second = publish()

        posts.setLike(viewerId, first, active = true)
        consent.granted = false
        posts.setLike(viewerId, second, active = true)

        assertEquals(
            listOf(first),
            events.written.filter { it.eventType == RecommendationEventType.CONTENT_LIKED }.map { it.postId },
        )
    }
}

/**
 * Profil sıfırlama ile rıza geri çekme iki ayrı işlem.
 *
 * İkisi de profili siliyor ama farklı sözler veriyor: sıfırlama "öğrendiğini
 * unut, toplamaya devam et", geri çekme "unut ve bir daha toplama". Aynı
 * testte ölçülürlerse birinin bozulması diğerinin arkasına saklanır.
 */
class ProfileResetAndWithdrawalTest {
    private val now = Instant.parse("2026-08-23T12:00:00Z")
    private val userId = UUID.randomUUID()
    private val repository = RecordingRepository()
    private val consents = MutableConsentRepository()
    private val service = RecommendationService(
        repository = repository,
        postRepository = EmptyPosts,
        ranker = ContextualRanker(),
        topics = TopicResolver(InMemoryTopicRepository()),
        clock = Clock.fixed(now, ZoneOffset.UTC),
        consents = consents,
    )

    private fun interestEvent(suffix: Int = 1) = RecommendationEventBatchRequest(
        listOf(
            RecommendationEventRequest(
                clientEventId = "00000000-0000-0000-0000-0000000000${"%02d".format(suffix)}",
                sessionId = "00000000-0000-0000-0000-000000000012",
                eventType = RecommendationEventType.INTEREST_SELECTED,
                surface = "onboarding",
                localHour = 11,
                timezoneOffsetMinutes = 180,
                targetFeature = "teknoloji",
                occurredAt = now.minusSeconds(5).toString(),
            )
        )
    )

    @Test
    fun `resetting clears the profile but keeps collecting`() {
        consents.granted = true
        service.append(userId, interestEvent())
        assertEquals(1, repository.written.size)

        service.reset(userId)

        assertTrue(repository.cleared.contains(userId))
        assertTrue(service.consent(userId).granted, "sıfırlama rızayı kapatmamalı")

        // Sıfırlamadan sonra yeni olaylar yine kabul edilmeli.
        assertEquals(1, service.append(userId, interestEvent(suffix = 2)).accepted)
    }

    @Test
    fun `withdrawing consent clears the profile and stops collecting`() {
        consents.granted = true
        service.append(userId, interestEvent())

        service.setConsent(userId, UpdateConsentRequest(granted = false))

        assertTrue(repository.cleared.contains(userId), "geri çekme profili de silmeli")
        assertFalse(service.consent(userId).granted)
        // Toplama durmalı: yeni olay yazılmamalı.
        assertEquals(0, service.append(userId, interestEvent(suffix = 3)).accepted)
    }

    @Test
    fun `granting consent again does not resurrect the old profile`() {
        consents.granted = true
        service.append(userId, interestEvent())
        service.setConsent(userId, UpdateConsentRequest(granted = false))
        repository.written.clear()

        service.setConsent(userId, UpdateConsentRequest(granted = true))

        assertTrue(repository.written.isEmpty(), "silinen veri geri gelmemeli")
        assertEquals(1, service.append(userId, interestEvent(suffix = 4)).accepted)
    }

    @Test
    fun `granting consent does not clear anything`() {
        consents.granted = false
        val before = repository.cleared.size

        service.setConsent(userId, UpdateConsentRequest(granted = true))

        assertEquals(before, repository.cleared.size, "rıza vermek silme işlemi değil")
    }
}

private class TogglableConsent : PersonalizationConsent {
    var granted = true
    override fun isGranted(userId: UUID) = granted
}

private class MutableConsentRepository : ConsentRepository {
    var granted = false
    private var updatedAt: Instant? = null

    override fun find(userId: UUID) = RecommendationConsent(granted, ConsentContract.VERSION, updatedAt)

    override fun set(userId: UUID, granted: Boolean, contractVersion: Int, now: Instant): RecommendationConsent {
        this.granted = granted
        updatedAt = now
        return RecommendationConsent(granted, contractVersion, now)
    }
}

private class RecordingRepository : RecommendationRepository {
    val written = mutableListOf<RecommendationEvent>()
    val cleared = mutableListOf<UUID>()
    override val personalizationAvailable = true

    override fun append(events: List<RecommendationEvent>): Int {
        val known = written.map { it.userId to it.clientEventId }.toMutableSet()
        val fresh = events.filter { known.add(it.userId to it.clientEventId) }
        written += fresh
        return fresh.size
    }

    override fun recentSignals(userId: UUID, limit: Int, notAfter: Instant?) = emptyList<RecommendationSignal>()
    override fun profileStats(userId: UUID) = RecommendationProfileStats(written.size.toLong(), null)
    override fun clear(userId: UUID) {
        cleared += userId
        written.removeAll { it.userId == userId }
    }
    override fun export(userId: UUID) = RecommendationExport()
    override fun deleteOlderThan(cutoff: Instant, batchSize: Int) = 0
    override fun hide(userId: UUID, postId: UUID, now: Instant) = Unit
}

private object EmptyPosts : com.nexi.posts.PostRepository {
    override fun create(
        ownerId: UUID,
        body: String,
        mediaIds: List<UUID>,
        topicIds: List<UUID>,
        now: Instant,
    ) = error("Testte kullanılmıyor")
    override fun findDetails(postId: UUID, viewerId: UUID) = null
    override fun feed(viewerId: UUID, cursor: com.nexi.posts.FeedCursor?, limit: Int) = error("Testte kullanılmıyor")
    override fun postsByOwner(
        ownerId: UUID,
        viewerId: UUID,
        cursor: com.nexi.posts.FeedCursor?,
        limit: Int,
    ) = error("Testte kullanılmıyor")
    override fun candidates(
        viewerId: UUID,
        source: com.nexi.posts.CandidateSource,
        priorityTopicCount: Int,
        notAfter: Instant,
        limit: Int,
    ) = error("Testte kullanılmıyor")
    override fun hydrate(details: List<com.nexi.posts.PostDetails>) = error("Testte kullanılmıyor")
    override fun markDeleted(postId: UUID, ownerId: UUID, now: Instant) = error("Testte kullanılmıyor")
    override fun setLike(postId: UUID, userId: UUID, active: Boolean, now: Instant) = error("Testte kullanılmıyor")
    override fun setSave(postId: UUID, userId: UUID, active: Boolean, now: Instant) = error("Testte kullanılmıyor")
    override fun search(
        viewerId: UUID,
        query: String,
        cursor: com.nexi.posts.RankedPostCursor?,
        limit: Int,
    ) = error("Testte kullanılmıyor")
    override fun explore(
        viewerId: UUID,
        rankedAt: Instant,
        cursor: com.nexi.posts.RankedPostCursor?,
        limit: Int,
    ) = error("Testte kullanılmıyor")
}
