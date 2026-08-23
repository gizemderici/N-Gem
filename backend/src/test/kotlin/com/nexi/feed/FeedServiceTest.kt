package com.nexi.feed

import com.nexi.posts.CreatePostRequest
import com.nexi.posts.FakeObjectStorage
import com.nexi.posts.InMemoryPostRepository
import com.nexi.posts.PostService
import com.nexi.topics.InMemoryTopicRepository
import com.nexi.topics.Topic
import com.nexi.topics.UpdateUserTopicsRequest
import com.nexi.topics.TopicService
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FeedServiceTest {
    private val now = Instant.parse("2026-08-23T00:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val viewerId = UUID.randomUUID()

    private val topics = InMemoryTopicRepository()
    private val postRepository = InMemoryPostRepository(viewerId, now, topics)
    private val storage = FakeObjectStorage()
    private val posts = PostService(postRepository, storage, clock)
    private val topicService = TopicService(topics, clock)
    private val service = FeedService(postRepository, topics, storage)

    private fun topic(slug: String) = topics.topic(slug)

    private fun publish(count: Int, topic: Topic? = null) = repeat(count) { index ->
        posts.create(
            viewerId,
            CreatePostRequest(
                text = "${topic?.slug ?: "konusuz"} $index",
                topicIds = listOfNotNull(topic?.id?.toString()),
            ),
        )
    }

    private fun selectTopics(vararg slugs: String) {
        topicService.replace(viewerId, UpdateUserTopicsRequest(slugs.map { topic(it).id.toString() }))
    }

    @Test
    fun `a page follows the seventy twenty ten mix`() {
        topics.relate(topic("teknoloji"), topic("bilim"), 0.7)
        selectTopics("teknoloji", "egitim", "spor", "muzik")
        publish(20, topic("teknoloji"))
        publish(20, topic("muzik"))
        publish(20, topic("bilim"))
        publish(20, topic("seyahat"))

        val page = service.feed(viewerId, null, 10)

        assertTrue(page.personalized)
        assertEquals(10, page.items.size)
        assertEquals(7, page.mix.primary)
        assertEquals(2, page.mix.related)
        assertEquals(1, page.mix.discovery)
    }

    @Test
    fun `the mix keeps its ratio across page boundaries`() {
        topics.relate(topic("teknoloji"), topic("bilim"), 0.7)
        selectTopics("teknoloji", "egitim", "spor", "muzik")
        publish(20, topic("teknoloji"))
        publish(20, topic("muzik"))
        publish(20, topic("bilim"))
        publish(20, topic("seyahat"))

        // Desenin ilk üç slotu: ana, ana, ilişkili.
        val first = service.feed(viewerId, null, 3)
        assertEquals(2, first.mix.primary)
        assertEquals(1, first.mix.related)
        val cursor = assertNotNull(first.nextCursor)

        // İkinci sayfa 4. slottan devam eder: ana, ana, alt sıradaki ilgi alanı.
        val second = service.feed(viewerId, cursor, 3)
        assertEquals(3, second.mix.primary)
        assertEquals(0, second.mix.related)
        assertTrue(first.items.map { it.post.id }.intersect(second.items.map { it.post.id }.toSet()).isEmpty())
    }

    @Test
    fun `reasons name the topic that produced the recommendation`() {
        topics.relate(topic("teknoloji"), topic("bilim"), 0.7)
        selectTopics("teknoloji", "egitim", "spor", "muzik")
        publish(6, topic("teknoloji"))
        publish(6, topic("muzik"))
        publish(6, topic("bilim"))
        publish(6, topic("seyahat"))

        val items = service.feed(viewerId, null, 10).items
        val byCode = items.associateBy { it.reason.code }

        assertEquals("Teknoloji, ilgi sıralamanda 1. sırada.", byCode.getValue("TOPIC_PRIORITY").reason.text)
        assertEquals("Müzik, seçtiğin ilgi alanlarından biri.", byCode.getValue("TOPIC_MATCH").reason.text)
        assertEquals("Seçtiğin Teknoloji alanıyla ilişkili: Bilim.", byCode.getValue("RELATED_TOPIC").reason.text)
        assertEquals(
            "Keşif payından geldi; ilgi alanlarının dışında yeni bir konu.",
            byCode.getValue("DISCOVERY").reason.text,
        )
        assertEquals("PRIMARY", byCode.getValue("TOPIC_PRIORITY").source)
        assertEquals("RELATED", byCode.getValue("RELATED_TOPIC").source)
        assertEquals("DISCOVERY", byCode.getValue("DISCOVERY").source)
    }

    @Test
    fun `empty tiers are filled from the user's own topics instead of shrinking the page`() {
        selectTopics("teknoloji", "egitim", "spor")
        publish(10, topic("teknoloji"))

        val page = service.feed(viewerId, null, 10)

        assertEquals(10, page.items.size)
        assertEquals(10, page.mix.primary)
        assertTrue(page.items.all { it.reason.code == "TOPIC_PRIORITY" })
    }

    @Test
    fun `a user without a selection gets the chronological feed`() {
        publish(3, topic("teknoloji"))

        val page = service.feed(viewerId, null, 10)

        assertFalse(page.personalized)
        assertEquals(3, page.items.size)
        assertEquals("NO_TOPICS_SELECTED", page.items.first().reason.code)
        assertNull(page.nextCursor)
    }

    @Test
    fun `paging walks the whole feed without repeats and then stops`() {
        selectTopics("teknoloji", "egitim", "spor")
        publish(7, topic("teknoloji"))
        publish(5, topic("seyahat"))

        val seen = mutableListOf<String>()
        var cursor: String? = null
        var pages = 0
        do {
            val page = service.feed(viewerId, cursor, 5)
            seen += page.items.map { it.post.id }
            cursor = page.nextCursor
            pages++
        } while (cursor != null && pages < 10)

        assertNull(cursor)
        assertEquals(12, seen.size)
        assertEquals(seen.size, seen.distinct().size)
    }

    @Test
    fun `posts from followed authors win over topic matching`() {
        val followedId = UUID.randomUUID()
        postRepository.authorNames[followedId] = "Mert Arslan"
        postRepository.follows += viewerId to followedId

        // Bütün katmanlar dolu olmalı; biri boş kalırsa slotları geri takibe
        // düşer ve desenin gerçek payı ölçülemez.
        topics.relate(topic("teknoloji"), topic("bilim"), 0.7)
        selectTopics("teknoloji", "egitim", "spor", "muzik")
        publish(20, topic("teknoloji"))
        publish(20, topic("muzik"))
        publish(20, topic("bilim"))
        publish(20, topic("seyahat"))

        // Takip edilen yazar tam da öncelikli konudan paylaşıyor: yine de takip
        // katmanına düşmeli, yoksa gönderi iki katmanda birden görünürdü.
        repeat(4) { index ->
            posts.create(
                followedId,
                CreatePostRequest(text = "takipten $index", topicIds = listOf(topic("teknoloji").id.toString())),
            )
        }

        val page = service.feed(viewerId, null, 10)

        // Desendeki iki takip slotu; ilişkili %20 ve keşif %10 bozulmadan duruyor.
        assertEquals(2, page.mix.following)
        assertEquals(5, page.mix.primary)
        assertEquals(2, page.mix.related)
        assertEquals(1, page.mix.discovery)
        val followed = page.items.first { it.source == "FOLLOWING" }
        assertEquals("FOLLOWING", followed.reason.code)
        assertEquals("Takip ettiğin Mert Arslan paylaştı.", followed.reason.text)
    }

    @Test
    fun `without any follows the mix falls back to the old seventy twenty ten`() {
        topics.relate(topic("teknoloji"), topic("bilim"), 0.7)
        selectTopics("teknoloji", "egitim", "spor", "muzik")
        publish(20, topic("teknoloji"))
        publish(20, topic("muzik"))
        publish(20, topic("bilim"))
        publish(20, topic("seyahat"))

        val page = service.feed(viewerId, null, 10)

        assertEquals(0, page.mix.following)
        assertEquals(7, page.mix.primary)
        assertEquals(2, page.mix.related)
        assertEquals(1, page.mix.discovery)
    }

    @Test
    fun `a followed author's post appears only once across pages`() {
        val followedId = UUID.randomUUID()
        postRepository.follows += viewerId to followedId

        selectTopics("teknoloji", "egitim", "spor")
        repeat(6) { index ->
            posts.create(
                followedId,
                CreatePostRequest(text = "takipten $index", topicIds = listOf(topic("teknoloji").id.toString())),
            )
        }
        publish(6, topic("teknoloji"))

        val seen = mutableListOf<String>()
        var cursor: String? = null
        var pages = 0
        do {
            val page = service.feed(viewerId, cursor, 5)
            seen += page.items.map { it.post.id }
            cursor = page.nextCursor
            pages++
        } while (cursor != null && pages < 10)

        assertEquals(12, seen.size)
        assertEquals(seen.size, seen.distinct().size)
    }

    @Test
    fun `a broken cursor is rejected`() {
        selectTopics("teknoloji", "egitim", "spor")

        val error = runCatching { service.feed(viewerId, "bozuk-imlec", 10) }.exceptionOrNull()

        assertEquals("INVALID_CURSOR", (error as com.nexi.auth.ApiException).code)
    }
}
