package com.nexi.recommendations

import com.nexi.posts.Post
import com.nexi.posts.PostAuthorResponse
import com.nexi.posts.PostDetails
import com.nexi.posts.PostStatus
import com.nexi.topics.InMemoryTopicRepository
import com.nexi.topics.Topic
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ContextualRankerTest {
    private val now = Instant.parse("2026-08-23T12:00:00Z")
    private val viewerId = UUID.fromString("00000000-0000-0000-0000-000000000001")
    private val catalog = InMemoryTopicRepository()

    private val technology = candidate("00000000-0000-0000-0000-000000000101", "Yeni bir yazılım kütüphanesi çıktı")
    private val gaming = candidate("00000000-0000-0000-0000-000000000102", "Akşam konsol başında uzun seans")
    private val ranker = ContextualRanker()

    @Test
    fun `same user gets work content by day and gaming by evening`() {
        val signals = listOf(
            interest("teknoloji", 11),
            interest("oyun", 21),
        )

        val daytime = ranker.rank(viewerId, listOf(gaming, technology), signals, context(11), now)
        val evening = ranker.rank(viewerId, listOf(technology, gaming), signals, context(21), now)

        assertEquals(technology.post.id, daytime.first().details.post.id)
        assertEquals(gaming.post.id, evening.first().details.post.id)
    }

    @Test
    fun `hidden content creates a strong negative signal`() {
        val hidden = RecommendationSignal(
            eventType = RecommendationEventType.CONTENT_HIDDEN,
            postId = gaming.post.id,
            authorId = gaming.post.ownerId,
            body = gaming.post.body,
            mediaType = "text",
            dwellMillis = null,
            completionRatio = null,
            localHour = 21,
            targetFeature = null,
            occurredAt = now.minusSeconds(60),
        )

        val ranked = ranker.rank(viewerId, listOf(gaming, technology), listOf(hidden), context(21), now)

        assertEquals(technology.post.id, ranked.first().details.post.id)
        assertTrue(ranked.last().score < ranked.first().score)
    }

    @Test
    fun `profile exposes explainable interests without raw text`() {
        val (interests, periods) = ranker.profile(listOf(interest("teknoloji", 11)), 11, now)

        assertEquals("topic:teknoloji", interests.first().key)
        assertEquals("Teknoloji", interests.first().label)
        assertEquals(4, periods.size)
    }

    // ------------------------------------------------- konu kimliği köprüsü

    @Test
    fun `a tagged post carries its topic even when the text never mentions it`() {
        // Kabul kriteri: konu özelliği metinden bağımsız olmalı. Eskiden özellik
        // yalnızca gövdedeki kelimelerden çıkarıldığı için, yazarın açıkça
        // "teknoloji" seçtiği bu gönderi hiçbir konu taşımıyordu.
        val neutralText = "Bugün sahilde yürüdüm ve kahve içtim"
        val tagged = candidate("00000000-0000-0000-0000-000000000201", neutralText, topic("teknoloji"))
        val untagged = candidate("00000000-0000-0000-0000-000000000202", neutralText)

        val ranked = ranker.rank(viewerId, listOf(untagged, tagged), listOf(interest("teknoloji", 11)), context(11), now)

        assertEquals(tagged.post.id, ranked.first().details.post.id)
        assertEquals("Bu saatte Teknoloji ilgine uygun", ranked.first().reason)
    }

    @Test
    fun `the author's own topics win over what the text looks like`() {
        // Metin baştan sona yazılım kelimeleriyle dolu, ama yazar konuyu "oyun"
        // seçmiş. Beyan tahmine üstün gelmeli; yoksa etiketleme hiçbir şey
        // değiştirmiyor demektir.
        val declaredGaming = candidate(
            "00000000-0000-0000-0000-000000000203",
            "Yazılım ve kodlama üzerine uzun bir donanım yazısı",
            topic("oyun"),
        )

        val ranked = ranker.rank(viewerId, listOf(declaredGaming), listOf(interest("oyun", 11)), context(11), now)

        assertEquals("Bu saatte Oyun ilgine uygun", ranked.single().reason)
    }

    @Test
    fun `a liked post teaches its topic through the signal's own tags`() {
        // Sinyal tarafı da aynı köprüye ihtiyaç duyar: beğenilen gönderinin
        // konusu metninden değil, etiketinden okunmalı.
        val liked = RecommendationSignal(
            eventType = RecommendationEventType.CONTENT_LIKED,
            postId = UUID.randomUUID(),
            authorId = UUID.randomUUID(),
            body = "Bugün sahilde yürüdüm ve kahve içtim",
            topicSlugs = listOf("muzik"),
            mediaType = "text",
            dwellMillis = null,
            completionRatio = null,
            localHour = 11,
            targetFeature = null,
            occurredAt = now.minusSeconds(60),
        )

        val (interests, _) = ranker.profile(listOf(liked), 11, now)

        assertTrue(interests.any { it.key == "topic:muzik" && it.label == "Müzik" }, "gerçek etiket profile yansımalı")
    }

    @Test
    fun `serving a post is not evidence of interest`() {
        // Sunum ve gösterim tercih kanıtı değil; ikisi de profile girmemeli.
        val served = listOf(RecommendationEventType.FEED_SERVED, RecommendationEventType.CONTENT_IMPRESSION).map { type ->
            RecommendationSignal(
                eventType = type,
                postId = technology.post.id,
                authorId = technology.post.ownerId,
                body = technology.post.body,
                topicSlugs = listOf("teknoloji"),
                mediaType = "text",
                dwellMillis = null,
                completionRatio = null,
                localHour = 11,
                targetFeature = null,
                occurredAt = now.minusSeconds(60),
            )
        }

        val (interests, _) = ranker.profile(served, 11, now)

        assertTrue(interests.isEmpty(), "sunum ve gösterim ilgi üretmemeli")
    }

    private fun context(hour: Int) = FeedRecommendationContext(hour, 180, UUID.randomUUID())

    private fun topic(slug: String): Topic = catalog.topic(slug)

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

    private fun candidate(id: String, body: String, vararg topics: Topic): PostDetails {
        val postId = UUID.fromString(id)
        val ownerId = UUID.nameUUIDFromBytes("owner-$id".toByteArray())
        val post = Post(postId, ownerId, body, PostStatus.PUBLISHED, now.minusSeconds(300), now.minusSeconds(300))
        return PostDetails(
            post = post,
            author = PostAuthorResponse(ownerId.toString(), "İçerik Üreticisi", "uretici"),
            media = emptyList(),
            topics = topics.toList(),
            likeCount = 0,
            saveCount = 0,
            commentCount = 0,
            likedByViewer = false,
            savedByViewer = false,
        )
    }
}
