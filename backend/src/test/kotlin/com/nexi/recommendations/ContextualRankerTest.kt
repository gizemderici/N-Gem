package com.nexi.recommendations

import com.nexi.posts.Post
import com.nexi.posts.PostAuthorResponse
import com.nexi.posts.PostDetails
import com.nexi.posts.PostStatus
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ContextualRankerTest {
    private val now = Instant.parse("2026-08-23T12:00:00Z")
    private val viewerId = UUID.fromString("00000000-0000-0000-0000-000000000001")
    private val technology = candidate("00000000-0000-0000-0000-000000000101", "Yapay zeka ve mobil teknoloji gündemi")
    private val comedy = candidate("00000000-0000-0000-0000-000000000102", "Akşam için kısa komik mizah videosu")
    private val ranker = ContextualRanker()

    @Test
    fun `same user gets work content by day and comedy by evening`() {
        val signals = listOf(
            interest("technology", 11),
            interest("comedy", 21),
        )

        val daytime = ranker.rank(viewerId, listOf(comedy, technology), signals, context(11), now)
        val evening = ranker.rank(viewerId, listOf(technology, comedy), signals, context(21), now)

        assertEquals(technology.post.id, daytime.first().details.post.id)
        assertEquals(comedy.post.id, evening.first().details.post.id)
    }

    @Test
    fun `hidden content creates a strong negative signal`() {
        val hidden = RecommendationSignal(
            eventType = RecommendationEventType.CONTENT_HIDDEN,
            postId = comedy.post.id,
            authorId = comedy.post.ownerId,
            body = comedy.post.body,
            mediaType = "text",
            dwellMillis = null,
            completionRatio = null,
            localHour = 21,
            targetFeature = null,
            occurredAt = now.minusSeconds(60),
        )

        val ranked = ranker.rank(viewerId, listOf(comedy, technology), listOf(hidden), context(21), now)

        assertEquals(technology.post.id, ranked.first().details.post.id)
        assertTrue(ranked.last().score < ranked.first().score)
    }

    @Test
    fun `profile exposes explainable interests without raw text`() {
        val (interests, periods) = ranker.profile(listOf(interest("technology", 11)), 11, now)

        assertEquals("topic:technology", interests.first().key)
        assertEquals("Teknoloji", interests.first().label)
        assertEquals(4, periods.size)
    }

    private fun context(hour: Int) = FeedRecommendationContext(hour, 180, UUID.randomUUID())

    private fun interest(topic: String, hour: Int) = RecommendationSignal(
        eventType = RecommendationEventType.INTEREST_SELECTED,
        postId = null,
        authorId = null,
        body = null,
        mediaType = null,
        dwellMillis = null,
        completionRatio = null,
        localHour = hour,
        targetFeature = topic,
        occurredAt = now.minusSeconds(120),
    )

    private fun candidate(id: String, body: String): PostDetails {
        val postId = UUID.fromString(id)
        val ownerId = UUID.nameUUIDFromBytes("owner-$id".toByteArray())
        val post = Post(postId, ownerId, body, PostStatus.PUBLISHED, now.minusSeconds(300), now.minusSeconds(300))
        return PostDetails(
            post = post,
            author = PostAuthorResponse(ownerId.toString(), "İçerik Üreticisi", "uretici"),
            media = emptyList(),
            likeCount = 0,
            saveCount = 0,
            likedByViewer = false,
            savedByViewer = false,
        )
    }
}
