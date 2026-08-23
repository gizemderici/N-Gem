package com.nexi.posts

import com.nexi.auth.ApiException
import com.nexi.topics.InMemoryTopicRepository
import io.ktor.http.HttpStatusCode
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

class PostServiceTest {
    private val now = Instant.parse("2026-08-23T00:00:00Z")
    private val ownerId = UUID.randomUUID()
    private val topics = InMemoryTopicRepository()
    private val repository = InMemoryPostRepository(ownerId, now, topics)
    private val storage = FakeObjectStorage()
    private val service = PostService(repository, storage, Clock.fixed(now, ZoneOffset.UTC))

    @Test
    fun `ready owned media can be attached to a post`() {
        val media = repository.addReadyMedia(ownerId)

        val post = service.create(ownerId, CreatePostRequest("NEXI ile ilk gönderi", listOf(media.id.toString())))

        assertEquals("NEXI ile ilk gönderi", post.text)
        assertEquals(media.id.toString(), post.media.single().id)
        assertTrue(post.media.single().url.contains(media.storageKey))
    }

    @Test
    fun `empty posts and foreign media are rejected`() {
        assertEquals("EMPTY_POST", assertFailsWith<ApiException> {
            service.create(ownerId, CreatePostRequest())
        }.code)

        val foreignMedia = repository.addReadyMedia(UUID.randomUUID())
        assertEquals("MEDIA_NOT_AVAILABLE", assertFailsWith<ApiException> {
            service.create(ownerId, CreatePostRequest(mediaIds = listOf(foreignMedia.id.toString())))
        }.code)
    }

    @Test
    fun `same media cannot be attached twice`() {
        val media = repository.addReadyMedia(ownerId)
        service.create(ownerId, CreatePostRequest(mediaIds = listOf(media.id.toString())))

        val error = assertFailsWith<ApiException> {
            service.create(ownerId, CreatePostRequest("İkinci", listOf(media.id.toString())))
        }
        assertEquals(HttpStatusCode.Conflict, error.status)
        assertEquals("MEDIA_ALREADY_ATTACHED", error.code)
    }

    @Test
    fun `feed has cursor and interaction state is viewer specific`() {
        repeat(3) { service.create(ownerId, CreatePostRequest("Gönderi $it")) }

        val firstPage = service.feed(ownerId, null, 2)
        assertEquals(2, firstPage.items.size)
        assertNotNull(firstPage.nextCursor)

        val firstPostId = UUID.fromString(firstPage.items.first().id)
        assertEquals(1, service.setLike(ownerId, firstPostId, true).count)
        assertEquals(1, service.setSave(ownerId, firstPostId, true).count)
        val refreshed = service.get(ownerId, firstPostId)
        assertTrue(refreshed.likedByMe)
        assertTrue(refreshed.savedByMe)
    }

    @Test
    fun `only owner can delete a post`() {
        val post = service.create(ownerId, CreatePostRequest("Silinebilir"))
        assertEquals(HttpStatusCode.NotFound, assertFailsWith<ApiException> {
            service.delete(UUID.randomUUID(), UUID.fromString(post.id))
        }.status)
        service.delete(ownerId, UUID.fromString(post.id))
        assertFailsWith<ApiException> { service.get(ownerId, UUID.fromString(post.id)) }
    }

    @Test
    fun `deleting a post releases its media from database and storage`() {
        val media = repository.addReadyMedia(ownerId)
        val post = service.create(ownerId, CreatePostRequest("Görselli gönderi", listOf(media.id.toString())))

        service.delete(ownerId, UUID.fromString(post.id))

        // Dosya depoda kalmamalı; eskiden hem satır hem nesne sonsuza kadar duruyordu.
        assertTrue(media.storageKey in storage.deletedKeys)
        // Görsel de silinmiş sayılır, yeniden bağlanamaz.
        assertEquals("MEDIA_NOT_AVAILABLE", assertFailsWith<ApiException> {
            service.create(ownerId, CreatePostRequest("Tekrar kullanmayı dene", listOf(media.id.toString())))
        }.code)
    }

    @Test
    fun `owner listing returns only that user's posts and skips deleted ones`() {
        val strangerId = UUID.randomUUID()
        service.create(ownerId, CreatePostRequest("Benim birinci"))
        val removable = service.create(ownerId, CreatePostRequest("Benim ikinci"))
        service.create(strangerId, CreatePostRequest("Baskasinin gonderisi"))

        // InMemoryPostRepository her yeni gönderiye bir saniye *eski* zaman damgası
        // veriyor, yani en yeniden eskiye sıralama oluşturma sırasıyla aynı oluyor.
        val mine = service.postsByOwner(ownerId, ownerId, null, null)
        assertEquals(listOf("Benim birinci", "Benim ikinci"), mine.items.map { it.text })

        service.delete(ownerId, UUID.fromString(removable.id))
        assertEquals(listOf("Benim birinci"), service.postsByOwner(ownerId, ownerId, null, null).items.map { it.text })

        assertEquals(1, service.postsByOwner(strangerId, ownerId, null, null).items.size)
    }

    @Test
    fun `owner listing pages with a cursor`() {
        repeat(3) { service.create(ownerId, CreatePostRequest("Gönderi $it")) }

        val firstPage = service.postsByOwner(ownerId, ownerId, null, 2)
        assertEquals(2, firstPage.items.size)
        val cursor = assertNotNull(firstPage.nextCursor)

        val secondPage = service.postsByOwner(ownerId, ownerId, cursor, 2)
        assertEquals(1, secondPage.items.size)
        assertNull(secondPage.nextCursor)

        val allIds = (firstPage.items + secondPage.items).map { it.id }
        assertEquals(allIds.distinct().size, allIds.size)
    }

    @Test
    fun `posts carry their topics and unknown topics are rejected`() {
        val teknoloji = topics.topic("teknoloji")

        val post = service.create(ownerId, CreatePostRequest("Konu etiketli gönderi", topicIds = listOf(teknoloji.id.toString())))
        assertEquals(listOf("teknoloji"), post.topics.map { it.slug })

        assertEquals("UNKNOWN_TOPIC", assertFailsWith<ApiException> {
            service.create(ownerId, CreatePostRequest("Bilinmeyen konu", topicIds = listOf(UUID.randomUUID().toString())))
        }.code)

        assertEquals("DUPLICATE_TOPIC", assertFailsWith<ApiException> {
            service.create(
                ownerId,
                CreatePostRequest("Yinelenen konu", topicIds = listOf(teknoloji.id.toString(), teknoloji.id.toString())),
            )
        }.code)
    }
}
