package com.nexi.search

import com.nexi.auth.ApiException
import com.nexi.posts.CreatePostRequest
import com.nexi.posts.FakeObjectStorage
import com.nexi.posts.InMemoryPostRepository
import com.nexi.posts.PostService
import com.nexi.topics.InMemoryTopicRepository
import com.nexi.topics.Topic
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SearchServiceTest {
    private val now = Instant.parse("2026-08-23T00:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val viewerId = UUID.randomUUID()

    private val topics = InMemoryTopicRepository()
    private val postRepository = InMemoryPostRepository(viewerId, now, topics)
    private val storage = FakeObjectStorage()
    private val posts = PostService(postRepository, storage, clock)
    private val searchRepository = FakeSearchRepository()
    private val service = SearchService(postRepository, searchRepository, storage)

    private fun publish(text: String) = posts.create(viewerId, CreatePostRequest(text = text))

    // ------------------------------------------------------------ dogrulama

    @Test
    fun `an empty query is refused`() {
        listOf(null, "", "   ").forEach { query ->
            assertEquals("EMPTY_QUERY", assertFailsWith<ApiException> {
                service.searchPosts(viewerId, query, null, null)
            }.code, "bos sorgu reddedilmeli: '$query'")
        }
    }

    @Test
    fun `an overlong query is refused`() {
        assertEquals("QUERY_TOO_LONG", assertFailsWith<ApiException> {
            service.searchPosts(viewerId, "a".repeat(SearchService.MAX_QUERY_LENGTH + 1), null, null)
        }.code)
    }

    @Test
    fun `a broken cursor is refused`() {
        assertEquals("INVALID_CURSOR", assertFailsWith<ApiException> {
            service.searchPosts(viewerId, "kotlin", "bozuk-imlec", null)
        }.code)
    }

    // --------------------------------------------------------------- arama

    @Test
    fun `posts are found by their text`() {
        publish("Kotlin ile backend yazmak keyifli")
        publish("Swift tarafinda bugun neler oldu")

        val result = service.searchPosts(viewerId, "kotlin", null, null)

        assertEquals(1, result.items.size)
        assertTrue(result.items.single().text.contains("Kotlin"))
    }

    @Test
    fun `search ignores case and Turkish diacritics`() {
        publish("Yazılım geliştirme üzerine çiçek gibi bir yazı")

        // Kullanici aksansiz yazsa da bulmali; unaccent'in yaptigi is.
        listOf("yazilim", "YAZILIM", "cicek", "gelistirme").forEach { query ->
            assertEquals(1, service.searchPosts(viewerId, query, null, null).items.size, "'$query' bulmali")
        }
    }

    @Test
    fun `hashtags are searchable without a separate table`() {
        publish("Bugun #kotlin ogrendim")

        assertEquals(1, service.searchPosts(viewerId, "kotlin", null, null).items.size)
    }

    @Test
    fun `deleted posts do not appear in search`() {
        val post = publish("Silinecek gonderi kotlin")
        posts.delete(viewerId, UUID.fromString(post.id))

        assertTrue(service.searchPosts(viewerId, "kotlin", null, null).items.isEmpty())
    }

    @Test
    fun `users are found and carry their follower count`() {
        searchRepository.addUser("Gizem Derici", "gizem", followers = 12)
        searchRepository.addUser("Mert Arslan", "mert")

        val result = service.searchUsers(viewerId, "gizem", null, null)

        assertEquals("gizem", result.items.single().username)
        assertEquals(12, result.items.single().followerCount)
    }

    @Test
    fun `partial username matches so search works while typing`() {
        searchRepository.addUser("Gizem Derici", "gizem")

        assertEquals(1, service.searchUsers(viewerId, "giz", null, null).items.size)
    }

    @Test
    fun `combined search returns every section`() {
        publish("Kotlin ve backend")
        searchRepository.addUser("Kotlin Turkiye", "kotlintr")
        searchRepository.topicResults += topics.topic("teknoloji")

        val result = service.searchAll(viewerId, "kotlin")

        assertEquals("kotlin", result.query)
        assertEquals(1, result.posts.size)
        assertEquals(1, result.users.size)
        assertEquals(1, result.topics.size)
    }

    @Test
    fun `combined search caps each section`() {
        repeat(10) { publish("kotlin $it") }

        assertEquals(SearchService.COMBINED_SECTION_SIZE, service.searchAll(viewerId, "kotlin").posts.size)
    }

    // -------------------------------------------------------------- kesfet

    @Test
    fun `explore needs no query and ranks by engagement`() {
        val quiet = publish("Sessiz gonderi")
        val popular = publish("Populer gonderi")
        postRepository.setLike(UUID.fromString(popular.id), UUID.randomUUID(), true, now)
        postRepository.setLike(UUID.fromString(popular.id), UUID.randomUUID(), true, now)

        val result = service.explore(viewerId, null, null)

        assertEquals(popular.id, result.items.first().id)
        assertTrue(result.items.any { it.id == quiet.id })
    }

    @Test
    fun `explore pages without repeats`() {
        repeat(5) { index ->
            val post = publish("Gonderi $index")
            // Farkli begeni sayilari puanlari ayirsin.
            repeat(index) { postRepository.setLike(UUID.fromString(post.id), UUID.randomUUID(), true, now) }
        }

        val first = service.explore(viewerId, null, 2)
        assertEquals(2, first.items.size)
        val second = service.explore(viewerId, assertNotNull(first.nextCursor), 2)
        val third = service.explore(viewerId, assertNotNull(second.nextCursor), 2)

        val ids = (first.items + second.items + third.items).map { it.id }
        assertEquals(5, ids.size)
        assertEquals(ids.distinct().size, ids.size)
        assertNull(third.nextCursor)
    }

    @Test
    fun `search pages without repeats`() {
        repeat(5) { publish("kotlin gonderi $it") }

        val first = service.searchPosts(viewerId, "kotlin", null, 2)
        val second = service.searchPosts(viewerId, "kotlin", assertNotNull(first.nextCursor), 2)

        val ids = (first.items + second.items).map { it.id }
        assertEquals(4, ids.size)
        assertEquals(ids.distinct().size, ids.size)
    }

    @Test
    fun `the page size is clamped`() {
        repeat(3) { publish("kotlin $it") }

        assertEquals(3, service.searchPosts(viewerId, "kotlin", null, 999).items.size)
        assertEquals(1, service.searchPosts(viewerId, "kotlin", null, 0).items.size)
    }
}

/** Kullanıcı ve konu araması için bellek içi karşılık. */
private class FakeSearchRepository : SearchRepository {
    private val users = mutableListOf<SearchUser>()
    val topicResults = mutableListOf<Topic>()

    private var sequence = 0L
    private val baseTime: Instant = Instant.parse("2026-08-23T00:00:00Z")

    fun addUser(fullName: String, username: String, followers: Long = 0) {
        users += SearchUser(
            id = UUID.randomUUID(),
            fullName = fullName,
            username = username,
            avatarStorageKey = null,
            followerCount = followers,
            followedByViewer = false,
            isViewer = false,
            rank = 1.0,
            createdAt = baseTime.plusSeconds(sequence++),
        )
    }

    override fun users(viewerId: UUID, query: String, cursor: RankedCursor?, limit: Int): List<SearchUser> {
        val needle = query.lowercase()
        return users
            .filter { it.fullName.lowercase().contains(needle) || it.username.lowercase().startsWith(needle) }
            .sortedWith(compareByDescending<SearchUser> { it.rank }.thenByDescending { it.createdAt })
            .filter { cursor == null || it.createdAt < cursor.createdAt }
            .take(limit)
    }

    override fun topics(query: String, limit: Int): List<Topic> = topicResults.take(limit)
}
