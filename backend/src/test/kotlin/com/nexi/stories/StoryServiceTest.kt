package com.nexi.stories

import com.nexi.auth.ApiException
import com.nexi.media.MediaProcessingStatus
import com.nexi.media.MediaStatus
import com.nexi.posts.FakeObjectStorage
import io.ktor.http.HttpStatusCode
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StoryServiceTest {
    private val now = Instant.parse("2026-08-23T00:00:00Z")
    private val repository = InMemoryStoryRepository(now)
    private val storage = FakeObjectStorage()
    private val service = StoryService(repository, storage, Clock.fixed(now, ZoneOffset.UTC))

    private val meId = repository.addUser("Gizem Derici", "gizem")
    private val friendId = repository.addUser("Mert Arslan", "mert")
    private val strangerId = repository.addUser("Ayse Yilmaz", "ayse")

    init {
        repository.follows += meId to friendId
    }

    private fun publish(ownerId: UUID, caption: String? = null): StoryResponse {
        val mediaId = repository.addMedia(ownerId)
        return service.create(ownerId, CreateStoryRequest(mediaId.toString(), caption))
    }

    // ------------------------------------------------------------ olusturma

    @Test
    fun `a story expires 24 hours after publishing`() {
        val story = publish(meId, "Gunun ani")

        assertEquals(now.toString(), story.publishedAt)
        assertEquals(now.plus(StoryService.LIFETIME).toString(), story.expiresAt)
        assertEquals("Gunun ani", story.caption)
    }

    @Test
    fun `media must be yours, ready and unused`() {
        assertEquals("MEDIA_NOT_AVAILABLE", assertFailsWith<ApiException> {
            service.create(meId, CreateStoryRequest(repository.addMedia(strangerId).toString()))
        }.code)

        assertEquals("MEDIA_NOT_READY", assertFailsWith<ApiException> {
            service.create(meId, CreateStoryRequest(repository.addMedia(meId, status = MediaStatus.PENDING).toString()))
        }.code)

        // Islenmesi bitmemis video da kabul edilmemeli.
        assertEquals("MEDIA_NOT_READY", assertFailsWith<ApiException> {
            service.create(
                meId,
                CreateStoryRequest(repository.addMedia(meId, processing = MediaProcessingStatus.PROCESSING).toString()),
            )
        }.code)
    }

    @Test
    fun `the same media cannot be used twice`() {
        val mediaId = repository.addMedia(meId)
        service.create(meId, CreateStoryRequest(mediaId.toString()))

        val error = assertFailsWith<ApiException> { service.create(meId, CreateStoryRequest(mediaId.toString())) }
        assertEquals(HttpStatusCode.Conflict, error.status)
        assertEquals("MEDIA_ALREADY_ATTACHED", error.code)
    }

    @Test
    fun `media already used by a post is refused`() {
        val mediaId = repository.addMedia(meId)
        repository.mediaInPosts += mediaId

        assertEquals("MEDIA_ALREADY_ATTACHED", assertFailsWith<ApiException> {
            service.create(meId, CreateStoryRequest(mediaId.toString()))
        }.code)
    }

    @Test
    fun `an overlong caption is refused`() {
        val mediaId = repository.addMedia(meId)

        assertEquals("CAPTION_TOO_LONG", assertFailsWith<ApiException> {
            service.create(meId, CreateStoryRequest(mediaId.toString(), "a".repeat(StoryService.MAX_CAPTION_LENGTH + 1)))
        }.code)
    }

    // ----------------------------------------------------------------- akis

    @Test
    fun `the feed carries your own and followed stories, grouped by author`() {
        publish(meId)
        publish(friendId)
        publish(friendId)
        publish(strangerId) // takip edilmiyor

        val feed = service.feed(meId)

        assertEquals(2, feed.items.size)
        assertEquals(setOf("gizem", "mert"), feed.items.map { it.author.username }.toSet())
        assertEquals(2, feed.items.single { it.author.username == "mert" }.stories.size)
    }

    @Test
    fun `expired stories drop out of the feed`() {
        publish(friendId)
        // Saat 25 saat ilerlesin: hikaye sona ermis olmali.
        val later = StoryService(repository, storage, Clock.fixed(now.plus(Duration.ofHours(25)), ZoneOffset.UTC))

        assertTrue(later.feed(meId).items.isEmpty())
    }

    @Test
    fun `groups with unseen stories come first`() {
        val mine = publish(meId)
        publish(friendId)
        // Kendi hikayemi gormus sayalim; arkadasinki gorulmemis kalsin.
        repository.markViewed(UUID.fromString(mine.id), meId, now)

        val feed = service.feed(meId)

        assertEquals("mert", feed.items.first().author.username)
        assertTrue(feed.items.first().hasUnseen)
        assertFalse(feed.items.last().hasUnseen)
    }

    @Test
    fun `blocked users disappear from the feed and their profile stories`() {
        publish(friendId)
        repository.blocks += meId to friendId

        assertTrue(service.feed(meId).items.isEmpty())
        assertEquals(HttpStatusCode.NotFound, assertFailsWith<ApiException> {
            service.byUsername(meId, "mert")
        }.status)
    }

    @Test
    fun `a user without stories returns an empty feed rather than an error`() {
        assertTrue(service.byUsername(meId, "mert").items.isEmpty())
    }

    // ------------------------------------------------------------ goruntuleme

    @Test
    fun `viewing is recorded once and shows up for the owner`() {
        val story = publish(friendId)
        val storyId = UUID.fromString(story.id)

        service.markViewed(meId, storyId)
        service.markViewed(meId, storyId)

        val viewers = service.viewers(friendId, storyId, null, null)
        assertEquals(1, viewers.totalCount)
        assertEquals("gizem", viewers.items.single().username)
    }

    @Test
    fun `looking at your own story is not counted as a view`() {
        val story = publish(meId)

        service.markViewed(meId, UUID.fromString(story.id))

        assertEquals(0, service.viewers(meId, UUID.fromString(story.id), null, null).totalCount)
    }

    @Test
    fun `only the owner sees the viewer list`() {
        val story = publish(friendId)
        service.markViewed(meId, UUID.fromString(story.id))

        // Yetkisiz kisiye 403 dönmek hikayenin varligini dogrulardi.
        val error = assertFailsWith<ApiException> { service.viewers(meId, UUID.fromString(story.id), null, null) }
        assertEquals(HttpStatusCode.NotFound, error.status)
        assertEquals("STORY_NOT_FOUND", error.code)
    }

    @Test
    fun `the view count is only shown to the owner`() {
        val story = publish(friendId)
        service.markViewed(meId, UUID.fromString(story.id))

        assertNull(service.feed(meId).items.single().stories.single().viewCount, "baskasi sayaci gormemeli")
        assertEquals(1, service.feed(friendId).items.single().stories.single().viewCount)
    }

    @Test
    fun `seenByMe reflects who is looking`() {
        val story = publish(friendId)
        service.markViewed(meId, UUID.fromString(story.id))

        assertTrue(service.feed(meId).items.single().stories.single().seenByMe)
    }

    // ---------------------------------------------------------------- silme

    @Test
    fun `only the owner can delete, and the media is released`() {
        val mediaId = repository.addMedia(meId)
        val story = service.create(meId, CreateStoryRequest(mediaId.toString()))
        val storyId = UUID.fromString(story.id)

        assertEquals(HttpStatusCode.NotFound, assertFailsWith<ApiException> {
            service.delete(friendId, storyId)
        }.status)

        service.delete(meId, storyId)

        assertTrue(repository.storageKeyOf(mediaId) in storage.deletedKeys)
        assertEquals(MediaStatus.DELETED, repository.mediaStatus(mediaId))
        assertTrue(service.feed(meId).items.isEmpty())
    }

    @Test
    fun `a missing or expired story is a not found`() {
        assertEquals("STORY_NOT_FOUND", assertFailsWith<ApiException> {
            service.markViewed(meId, UUID.randomUUID())
        }.code)
    }

    // -------------------------------------------------------------- temizlik

    @Test
    fun `the janitor closes expired stories and frees their media`() {
        val mediaId = repository.addMedia(friendId)
        service.create(friendId, CreateStoryRequest(mediaId.toString()))
        val later = now.plus(Duration.ofHours(25))
        val janitor = StoryJanitor(repository, storage, Clock.fixed(later, ZoneOffset.UTC))

        assertEquals(1, janitor.sweepExpired())

        assertTrue(repository.storageKeyOf(mediaId) in storage.deletedKeys)
        assertEquals(MediaStatus.DELETED, repository.mediaStatus(mediaId))
        // Ikinci supurmede toplanacak bir sey kalmamali.
        assertEquals(0, janitor.sweepExpired())
    }

    @Test
    fun `the janitor leaves live stories alone`() {
        publish(friendId)

        assertEquals(0, StoryJanitor(repository, storage, Clock.fixed(now, ZoneOffset.UTC)).sweepExpired())
        assertEquals(1, service.feed(meId).items.size)
    }

    @Test
    fun `a broken viewer cursor is refused`() {
        val story = publish(meId)

        assertEquals("INVALID_CURSOR", assertFailsWith<ApiException> {
            service.viewers(meId, UUID.fromString(story.id), "bozuk-imlec", null)
        }.code)
    }

    @Test
    fun `the viewer list pages`() {
        val story = publish(meId)
        val storyId = UUID.fromString(story.id)
        val watchers = (1..4).map { repository.addUser("Izleyici $it", "izleyici$it") }
        watchers.forEachIndexed { offset, id ->
            repository.markViewed(storyId, id, now.plusSeconds(offset.toLong()))
        }

        val first = service.viewers(meId, storyId, null, 2)
        assertEquals(2, first.items.size)
        assertEquals(4, first.totalCount)

        val second = service.viewers(meId, storyId, assertNotNull(first.nextCursor), 10)
        val all = (first.items + second.items).map { it.username }

        assertEquals(4, all.size)
        assertEquals(all.distinct().size, all.size)
    }
}
