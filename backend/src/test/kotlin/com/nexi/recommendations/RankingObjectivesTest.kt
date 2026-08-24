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
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Çok hedefli sıralama: ilgi, çeşitlilik, güvenlik ve üretici adaleti.
 *
 * Güvenlik ve adalet hedefleri **varsayılan olarak kapalı**. Temel sıralama
 * sistemi gerçek veriyle kanıtlanmadan yeni hedefleri herkese açmak,
 * ölçemediğimiz bir değişikliği üretime sokmak olurdu.
 */
class RankingObjectivesTest {
    private val now = Instant.parse("2026-08-23T21:00:00Z")
    private val viewerId = UUID.fromString("11111111-2222-4333-8444-555555555555")
    private val context = FeedRecommendationContext(21, 180, UUID.randomUUID())
    private val topics = InMemoryTopicRepository()

    private val authorA = UUID.fromString("dddddddd-0000-4000-8000-00000000000a")
    private val authorB = UUID.fromString("dddddddd-0000-4000-8000-00000000000b")

    @Test
    fun `the defaults reproduce the behaviour that shipped before`() {
        // Aynı senaryo iki sıralayıcıyla: varsayılan yapılandırma ile açıkça
        // eski değerleri veren yapılandırma aynı sonucu üretmeli.
        val candidates = (1..6).map { index ->
            post(index, if (index % 2 == 0) authorA else authorB, topics.topic("teknoloji"))
        }
        val signals = listOf(interest("teknoloji"))

        val default = ContextualRanker().rank(viewerId, candidates, signals, context, now)
        val explicit = ContextualRanker(
            RankingObjectives(authorPenalty = 0.55, topicOverlapPenalty = 0.25)
        ).rank(viewerId, candidates, signals, context, now)

        assertEquals(default.map { it.details.post.id }, explicit.map { it.details.post.id })
        assertEquals(default.map { it.score }, explicit.map { it.score })
    }

    @Test
    fun `safety suppresses content the user already rejected`() {
        // Olumsuz yakınlık kişiselleştirme bileşenine giriyor ama orada
        // **seyreliyor**: puan özellik sayısının kareköküne bölünüyor, yani
        // uzun metinli bir gönderide üç olumsuz özellik yirmi özelliğin içinde
        // kayboluyor. Taze ve popüler olduğunda kullanıcının açıkça gizlediği
        // içerik yine üste çıkabiliyor.
        val disliked = post(
            1, authorA, topics.topic("seyahat"),
            likeCount = 400,
            body = "Sahilde uzun bir yürüyüş sonrası kahve içtim ve manzara " +
                "gerçekten harikaydı, akşam üzeri hava serinleyince tekrar " +
                "çıkıp biraz daha dolaşmaya karar verdim",
        )
        val neutral = post(2, authorB, topics.topic("teknoloji"))
        val signals = listOf(hidden("seyahat", authorA))

        val without = ContextualRanker().rank(viewerId, listOf(disliked, neutral), signals, context, now)
        val with = ContextualRanker(RankingObjectives(safetyPenalty = 3.0))
            .rank(viewerId, listOf(disliked, neutral), signals, context, now)

        assertEquals(disliked.post.id, without.first().details.post.id, "kapalıyken popülerlik kazanıyor")
        assertEquals(neutral.post.id, with.first().details.post.id, "açıkken güvenlik kazanmalı")
    }

    @Test
    fun `safety leaves content the user never rejected alone`() {
        val candidates = listOf(post(1, authorA, topics.topic("teknoloji")), post(2, authorB, topics.topic("oyun")))
        val signals = listOf(interest("teknoloji"))

        val without = ContextualRanker().rank(viewerId, candidates, signals, context, now)
        val with = ContextualRanker(RankingObjectives(safetyPenalty = 3.0))
            .rank(viewerId, candidates, signals, context, now)

        assertEquals(
            without.map { it.details.post.id },
            with.map { it.details.post.id },
            "olumsuz sinyal yokken güvenlik hedefi sırayı değiştirmemeli",
        )
    }

    @Test
    fun `the creator cap limits how much of a page one author can take`() {
        // Yazar cezası doğrusal ve yeterince yüksek puanlı bir üretici
        // tarafından aşılabiliyor; üst sınır aşılamıyor.
        val dominant = (1..5).map { post(it, authorA, topics.topic("teknoloji"), likeCount = 500) }
        val others = (6..8).map { post(it, authorB, topics.topic("oyun")) }
        val signals = listOf(interest("teknoloji"))

        val capped = ContextualRanker(RankingObjectives(creatorCap = 2))
            .rank(viewerId, dominant + others, signals, context, now)

        val topThree = capped.take(3).map { it.details.post.ownerId }
        assertEquals(2, topThree.count { it == authorA }, "ilk üç slotta en fazla iki gönderi aynı yazardan")
    }

    @Test
    fun `the creator cap does not drop anyone from the results`() {
        // Sınır sıralamayı değiştirir, adayı elemez: eleme aday üretiminin
        // işi, sıralayıcının değil.
        val candidates = (1..5).map { post(it, authorA, topics.topic("teknoloji")) }

        val ranked = ContextualRanker(RankingObjectives(creatorCap = 1))
            .rank(viewerId, candidates, listOf(interest("teknoloji")), context, now)

        assertEquals(candidates.size, ranked.size)
        assertEquals(candidates.map { it.post.id }.toSet(), ranked.map { it.details.post.id }.toSet())
    }

    @Test
    fun `turning diversity off lets pure relevance decide`() {
        val candidates = (1..4).map { post(it, authorA, topics.topic("teknoloji")) }
        val signals = listOf(interest("teknoloji"))

        val flat = ContextualRanker(RankingObjectives(authorPenalty = 0.0, topicOverlapPenalty = 0.0))
            .rank(viewerId, candidates, signals, context, now)

        // Ceza yokken sıra doğrudan puana göre olmalı.
        assertEquals(flat.map { it.score }.sortedDescending(), flat.map { it.score })
    }

    @Test
    fun `negative weights are refused`() {
        listOf(
            { RankingObjectives(authorPenalty = -1.0) },
            { RankingObjectives(topicOverlapPenalty = -0.1) },
            { RankingObjectives(safetyPenalty = -2.0) },
            { RankingObjectives(creatorCap = -1) },
        ).forEach { build ->
            assertFailsWith<IllegalArgumentException> { build() }
        }
    }

    @Test
    fun `every objective is reachable from configuration`() {
        // Hedeflerin hepsi ayarlanabilir olmalı; sabit kodlanmış bir ağırlık
        // deney kolundan açılamaz.
        val tuned = RankingObjectives(
            authorPenalty = 0.9,
            topicOverlapPenalty = 0.4,
            safetyThreshold = -0.2,
            safetyPenalty = 2.0,
            creatorCap = 3,
            creatorCapPenalty = 7.0,
        )

        assertTrue(tuned.safetyPenalty > 0 && tuned.creatorCap > 0)
        assertEquals(0.0, RankingObjectives.DEFAULT.safetyPenalty, "varsayılanda güvenlik kapalı")
        assertEquals(0, RankingObjectives.DEFAULT.creatorCap, "varsayılanda sınır kapalı")
    }

    // ------------------------------------------------------------ yardimcilar

    private fun post(
        index: Int,
        ownerId: UUID,
        topic: Topic,
        likeCount: Long = 0,
        body: String = "Gönderi $index",
    ): PostDetails {
        val postId = UUID.fromString("aaaaaaaa-0000-4000-8000-%012d".format(index))
        val createdAt = now.minusSeconds(index * 600L)
        return PostDetails(
            post = Post(postId, ownerId, body, PostStatus.PUBLISHED, createdAt, createdAt),
            author = PostAuthorResponse(ownerId.toString(), "Üretici", "uretici"),
            media = emptyList(),
            topics = listOf(topic),
            likeCount = likeCount,
            saveCount = 0,
            commentCount = 0,
            likedByViewer = false,
            savedByViewer = false,
        )
    }

    private fun interest(slug: String) = RecommendationSignal(
        eventType = RecommendationEventType.INTEREST_SELECTED,
        postId = null,
        authorId = null,
        body = null,
        mediaType = null,
        dwellMillis = null,
        completionRatio = null,
        localHour = 21,
        targetFeature = slug,
        occurredAt = now.minusSeconds(3_600),
    )

    private fun hidden(slug: String, authorId: UUID) = RecommendationSignal(
        eventType = RecommendationEventType.CONTENT_HIDDEN,
        postId = UUID.randomUUID(),
        authorId = authorId,
        body = null,
        topicSlugs = listOf(slug),
        mediaType = "text",
        dwellMillis = null,
        completionRatio = null,
        localHour = 21,
        targetFeature = null,
        occurredAt = now.minusSeconds(7_200),
    )
}
