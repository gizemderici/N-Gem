package com.nexi.recommendations

import com.nexi.auth.ApiException
import com.nexi.posts.FeedCursor
import com.nexi.posts.FeedTier
import com.nexi.posts.Post
import com.nexi.posts.PostDetails
import com.nexi.posts.PostRepository
import com.nexi.posts.RankedPost
import com.nexi.posts.RankedPostCursor
import com.nexi.topics.InMemoryTopicRepository
import com.nexi.topics.TopicResolver
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class RecommendationServiceTest {
    private val now = Instant.parse("2026-08-23T12:00:00Z")
    private val userId = UUID.fromString("00000000-0000-0000-0000-000000000001")
    private val repository = InMemoryRecommendationRepository()
    private val service = RecommendationService(
        repository,
        EmptyPostRepository,
        ContextualRanker(),
        TopicResolver(InMemoryTopicRepository()),
        Clock.fixed(now, ZoneOffset.UTC),
    )

    @Test
    fun `client event id is idempotent and learned profile can be reset`() {
        val event = RecommendationEventRequest(
            clientEventId = "00000000-0000-0000-0000-000000000011",
            sessionId = "00000000-0000-0000-0000-000000000012",
            eventType = RecommendationEventType.INTEREST_SELECTED,
            surface = "onboarding",
            localHour = 11,
            timezoneOffsetMinutes = 180,
            targetFeature = "teknoloji",
            occurredAt = now.minusSeconds(5).toString(),
        )

        assertEquals(1, service.append(userId, RecommendationEventBatchRequest(listOf(event))).accepted)
        assertEquals(0, service.append(userId, RecommendationEventBatchRequest(listOf(event))).accepted)
        assertEquals("topic:teknoloji", service.profile(userId, 11).topInterests.first().key)

        service.reset(userId)
        assertEquals(0, service.profile(userId, 11).eventCount)
    }

    @Test
    fun `an interest outside the catalog is refused instead of stored`() {
        // Mobil uygulamalar bir zamanlar bu İngilizce kimlikleri gönderiyordu;
        // sessizce kabul edildikleri için hiçbir konuyla eşleşmeyen kayıtlar
        // birikmisti. Artik istemci hatayi aninda goruyor.
        listOf("technology", "comedy", "yok-boyle-konu").forEach { unknown ->
            assertEquals("UNKNOWN_TOPIC_FEATURE", assertFailsWith<ApiException> {
                service.append(userId, RecommendationEventBatchRequest(listOf(interest(unknown))))
            }.code, "'$unknown' reddedilmeli")
        }

        assertEquals(0, service.profile(userId, 11).eventCount)
    }

    @Test
    fun `an interest may arrive as a slug or a topic id and is stored as the slug`() {
        val topicId = InMemoryTopicRepository().topic("oyun").id.toString()

        service.append(userId, RecommendationEventBatchRequest(listOf(interest("oyun"))))
        service.append(userId, RecommendationEventBatchRequest(listOf(interest(topicId, suffix = 2))))
        // Buyuk harf Turkce yerel ayarinda "oyun" yerine noktasiz harf uretirdi.
        service.append(userId, RecommendationEventBatchRequest(listOf(interest("TEKNOLOJI", suffix = 3))))

        val keys = service.profile(userId, 11).topInterests.map { it.key }
        assertEquals(setOf("topic:oyun", "topic:teknoloji"), keys.toSet())
    }

    @Test
    fun `clients cannot forge events the server generates itself`() {
        // Sunum kaydını istemci uyduramaz; beğeni, kaydetme ve şikâyet ise
        // zaten backend işleminde yazılıyor, istemci de gönderirse ayni
        // etkilesim iki kez sayilirdi.
        RecommendationService.SERVER_GENERATED.forEach { type ->
            val forged = interest("oyun").copy(
                eventType = type,
                postId = "00000000-0000-0000-0000-0000000000ff",
            )

            assertEquals("SERVER_ONLY_EVENT", assertFailsWith<ApiException> {
                service.append(userId, RecommendationEventBatchRequest(listOf(forged)))
            }.code, "$type reddedilmeli")
        }
    }

    @Test
    fun `an event from a future contract version is refused`() {
        // Anlamini bilmedigimiz bir olayi yazmak veriyi sonradan ayiklanamaz
        // hale getirir.
        listOf(EventContract.CURRENT_VERSION + 1, 0, -1).forEach { version ->
            assertEquals("UNSUPPORTED_SCHEMA_VERSION", assertFailsWith<ApiException> {
                service.append(
                    userId,
                    RecommendationEventBatchRequest(listOf(interest("oyun").copy(schemaVersion = version))),
                )
            }.code, "sürüm $version reddedilmeli")
        }
    }

    @Test
    fun `an older but supported contract version is still accepted`() {
        val old = interest("oyun").copy(schemaVersion = EventContract.OLDEST_SUPPORTED_VERSION)

        assertEquals(1, service.append(userId, RecommendationEventBatchRequest(listOf(old))).accepted)
    }

    @Test
    fun `the client identifies itself and cannot claim to be the backend`() {
        val fromPhone = interest("oyun").copy(platform = "ios", appVersion = "1.4.2")
        assertEquals(1, service.append(userId, RecommendationEventBatchRequest(listOf(fromPhone))).accepted)

        listOf("backend", "windows-phone").forEach { platform ->
            assertEquals("INVALID_PLATFORM", assertFailsWith<ApiException> {
                service.append(
                    userId,
                    RecommendationEventBatchRequest(listOf(interest("oyun", suffix = 9).copy(platform = platform))),
                )
            }.code, "'$platform' reddedilmeli")
        }

        assertEquals("INVALID_APP_VERSION", assertFailsWith<ApiException> {
            service.append(
                userId,
                RecommendationEventBatchRequest(listOf(interest("oyun", suffix = 8).copy(appVersion = "v".repeat(21)))),
            )
        }.code)
    }

    @Test
    fun `content events require a post and interest events require a target`() {
        val base = RecommendationEventRequest(
            clientEventId = "00000000-0000-0000-0000-000000000021",
            sessionId = "00000000-0000-0000-0000-000000000022",
            eventType = RecommendationEventType.CONTENT_VIEW,
            surface = "feed",
            localHour = 21,
            timezoneOffsetMinutes = 180,
            occurredAt = now.toString(),
        )

        assertEquals("MISSING_POST_ID", assertFailsWith<ApiException> {
            service.append(userId, RecommendationEventBatchRequest(listOf(base)))
        }.code)
        assertEquals("MISSING_TARGET_FEATURE", assertFailsWith<ApiException> {
            service.append(
                userId,
                RecommendationEventBatchRequest(listOf(base.copy(eventType = RecommendationEventType.INTEREST_SELECTED)))
            )
        }.code)
    }

    private fun interest(target: String, suffix: Int = 1) = RecommendationEventRequest(
        clientEventId = "00000000-0000-0000-0000-0000000000${"%02d".format(suffix)}",
        sessionId = "00000000-0000-0000-0000-000000000012",
        eventType = RecommendationEventType.INTEREST_SELECTED,
        surface = "onboarding",
        localHour = 11,
        timezoneOffsetMinutes = 180,
        targetFeature = target,
        occurredAt = now.minusSeconds(5).toString(),
    )

}

private class InMemoryRecommendationRepository : RecommendationRepository {
    private val events = mutableListOf<RecommendationEvent>()
    override val personalizationAvailable = true

    override fun append(events: List<RecommendationEvent>): Int {
        val known = this.events.map { it.userId to it.clientEventId }.toMutableSet()
        val fresh = events.filter { known.add(it.userId to it.clientEventId) }
        this.events += fresh
        return fresh.size
    }

    override fun recentSignals(userId: UUID, limit: Int): List<RecommendationSignal> = events
        .filter { it.userId == userId }
        .sortedByDescending(RecommendationEvent::occurredAt)
        .take(limit)
        .map { event ->
            RecommendationSignal(
                eventType = event.eventType,
                postId = event.postId,
                authorId = null,
                body = null,
                mediaType = null,
                dwellMillis = event.dwellMillis,
                completionRatio = event.completionRatio,
                localHour = event.localHour,
                targetFeature = event.targetFeature,
                occurredAt = event.occurredAt,
            )
        }

    override fun profileStats(userId: UUID): RecommendationProfileStats {
        val selected = events.filter { it.userId == userId }
        return RecommendationProfileStats(selected.size.toLong(), selected.maxOfOrNull(RecommendationEvent::occurredAt))
    }

    override fun clear(userId: UUID) {
        events.removeAll { it.userId == userId }
    }
}

private object EmptyPostRepository : PostRepository {
    override fun create(
        ownerId: UUID,
        body: String,
        mediaIds: List<UUID>,
        topicIds: List<UUID>,
        now: Instant,
    ): Post = unsupported()
    override fun findDetails(postId: UUID, viewerId: UUID): PostDetails? = null
    override fun feed(viewerId: UUID, cursor: FeedCursor?, limit: Int): List<PostDetails> = unsupported()
    override fun postsByOwner(
        ownerId: UUID,
        viewerId: UUID,
        cursor: FeedCursor?,
        limit: Int,
    ): List<PostDetails> = unsupported()
    override fun feedTier(
        viewerId: UUID,
        tier: FeedTier,
        priorityTopicCount: Int,
        cursor: FeedCursor?,
        limit: Int,
    ): List<PostDetails> = unsupported()
    override fun hydrate(details: List<PostDetails>): List<PostDetails> = unsupported()
    override fun markDeleted(postId: UUID, ownerId: UUID, now: Instant): List<String>? = unsupported()
    override fun setLike(postId: UUID, userId: UUID, active: Boolean, now: Instant): Long = unsupported()
    override fun setSave(postId: UUID, userId: UUID, active: Boolean, now: Instant): Long = unsupported()
    override fun search(
        viewerId: UUID,
        query: String,
        cursor: RankedPostCursor?,
        limit: Int,
    ): List<RankedPost> = unsupported()
    override fun explore(viewerId: UUID, rankedAt: Instant, cursor: RankedPostCursor?, limit: Int): List<RankedPost> = unsupported()
}

private fun <T> unsupported(): T = throw UnsupportedOperationException("Testte kullanılmıyor")
