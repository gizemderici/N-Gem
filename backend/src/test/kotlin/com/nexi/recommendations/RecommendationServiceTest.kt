package com.nexi.recommendations

import com.nexi.auth.ApiException
import com.nexi.posts.FeedCursor
import com.nexi.posts.FeedTier
import com.nexi.posts.Post
import com.nexi.posts.PostDetails
import com.nexi.posts.PostRepository
import com.nexi.posts.RankedPost
import com.nexi.posts.RankedPostCursor
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
            targetFeature = "technology",
            occurredAt = now.minusSeconds(5).toString(),
        )

        assertEquals(1, service.append(userId, RecommendationEventBatchRequest(listOf(event))).accepted)
        assertEquals(0, service.append(userId, RecommendationEventBatchRequest(listOf(event))).accepted)
        assertEquals("topic:technology", service.profile(userId, 11).topInterests.first().key)

        service.reset(userId)
        assertEquals(0, service.profile(userId, 11).eventCount)
    }

    @Test
    fun `content events require a post and interest events require a target`() {
        val base = RecommendationEventRequest(
            clientEventId = "00000000-0000-0000-0000-000000000021",
            sessionId = "00000000-0000-0000-0000-000000000022",
            eventType = RecommendationEventType.CONTENT_LIKED,
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
    override fun explore(viewerId: UUID, cursor: RankedPostCursor?, limit: Int): List<RankedPost> = unsupported()
}

private fun <T> unsupported(): T = throw UnsupportedOperationException("Testte kullanılmıyor")
