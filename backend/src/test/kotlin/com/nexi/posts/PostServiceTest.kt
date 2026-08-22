package com.nexi.posts

import com.nexi.auth.ApiException
import com.nexi.media.MediaAsset
import com.nexi.media.MediaStatus
import com.nexi.media.ObjectStorage
import com.nexi.media.StoredObjectInfo
import io.ktor.http.HttpStatusCode
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PostServiceTest {
    private val now = Instant.parse("2026-08-23T00:00:00Z")
    private val ownerId = UUID.randomUUID()
    private val repository = FakePostRepository(ownerId, now)
    private val service = PostService(repository, FakePostStorage(), Clock.fixed(now, ZoneOffset.UTC))

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
}

private class FakePostRepository(private val ownerId: UUID, private val baseTime: Instant) : PostRepository {
    private val posts = linkedMapOf<UUID, Post>()
    private val media = mutableMapOf<UUID, MediaAsset>()
    private val attachedMedia = mutableSetOf<UUID>()
    private val likes = mutableSetOf<Pair<UUID, UUID>>()
    private val saves = mutableSetOf<Pair<UUID, UUID>>()
    private var sequence = 0L

    fun addReadyMedia(mediaOwnerId: UUID): MediaAsset {
        val id = UUID.randomUUID()
        return MediaAsset(
            id, mediaOwnerId, "users/$mediaOwnerId/media/$id.png", "test.png", "image/png",
            8, 8, MediaStatus.READY, baseTime, baseTime,
        ).also { media[id] = it }
    }

    override fun create(ownerId: UUID, body: String, mediaIds: List<UUID>, now: Instant): Post {
        mediaIds.forEach { id ->
            val asset = media[id]
            if (asset == null || asset.ownerId != ownerId || asset.status != MediaStatus.READY) throw MediaOwnershipException()
            if (id in attachedMedia) throw MediaAlreadyAttachedException()
        }
        val timestamp = now.minusSeconds(sequence++)
        val post = Post(UUID.randomUUID(), ownerId, body, PostStatus.PUBLISHED, timestamp, timestamp)
        posts[post.id] = post
        attachedMedia += mediaIds
        postMedia[post.id] = mediaIds
        return post
    }

    private val postMedia = mutableMapOf<UUID, List<UUID>>()

    override fun findDetails(postId: UUID, viewerId: UUID): PostDetails? {
        val post = posts[postId]?.takeIf { it.status == PostStatus.PUBLISHED } ?: return null
        return details(post, viewerId)
    }

    override fun feed(viewerId: UUID, cursor: FeedCursor?, limit: Int): List<PostDetails> = posts.values
        .filter { it.status == PostStatus.PUBLISHED }
        .sortedWith(compareByDescending<Post> { it.createdAt }.thenByDescending { it.id })
        .filter { cursor == null || it.createdAt < cursor.createdAt || (it.createdAt == cursor.createdAt && it.id < cursor.id) }
        .take(limit)
        .map { details(it, viewerId) }

    override fun markDeleted(postId: UUID, ownerId: UUID, now: Instant): Boolean {
        val post = posts[postId] ?: return false
        if (post.ownerId != ownerId || post.status != PostStatus.PUBLISHED) return false
        posts[postId] = post.copy(status = PostStatus.DELETED, updatedAt = now)
        return true
    }

    override fun setLike(postId: UUID, userId: UUID, active: Boolean, now: Instant): Long {
        if (active) likes += postId to userId else likes -= postId to userId
        return likes.count { it.first == postId }.toLong()
    }

    override fun setSave(postId: UUID, userId: UUID, active: Boolean, now: Instant): Long {
        if (active) saves += postId to userId else saves -= postId to userId
        return saves.count { it.first == postId }.toLong()
    }

    private fun details(post: Post, viewerId: UUID) = PostDetails(
        post = post,
        author = PostAuthorResponse(ownerId.toString(), "Gizem Derici", "gizem"),
        media = postMedia[post.id].orEmpty().mapNotNull(media::get),
        likeCount = likes.count { it.first == post.id }.toLong(),
        saveCount = saves.count { it.first == post.id }.toLong(),
        likedByViewer = post.id to viewerId in likes,
        savedByViewer = post.id to viewerId in saves,
    )
}

private class FakePostStorage : ObjectStorage {
    override fun createUploadUrl(key: String, mimeType: String, expiresIn: Duration) = error("unused")
    override fun inspect(key: String): StoredObjectInfo = error("unused")
    override fun createDownloadUrl(key: String, expiresIn: Duration) = "http://storage/$key"
    override fun delete(key: String) = Unit
}
