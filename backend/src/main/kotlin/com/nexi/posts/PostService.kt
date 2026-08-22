package com.nexi.posts

import com.nexi.auth.ApiException
import com.nexi.auth.validation
import com.nexi.media.ObjectStorage
import io.ktor.http.HttpStatusCode
import java.nio.charset.StandardCharsets
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.UUID

class PostService(
    private val repository: PostRepository,
    private val storage: ObjectStorage,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val mediaUrlExpiry = Duration.ofMinutes(15)

    fun create(ownerId: UUID, request: CreatePostRequest): PostResponse {
        val body = request.text.trim()
        if (body.length > 2_000) throw validation("POST_TOO_LONG", "Gönderi metni en fazla 2000 karakter olabilir.", "text")
        if (request.mediaIds.size > 4) throw validation("TOO_MANY_MEDIA", "Bir gönderiye en fazla dört görsel eklenebilir.", "mediaIds")

        val mediaIds = request.mediaIds.map { rawId ->
            runCatching { UUID.fromString(rawId) }.getOrElse {
                throw validation("INVALID_MEDIA_ID", "Görsel kimliği geçersiz.", "mediaIds")
            }
        }
        if (mediaIds.distinct().size != mediaIds.size) {
            throw validation("DUPLICATE_MEDIA", "Aynı görsel bir gönderiye birden fazla eklenemez.", "mediaIds")
        }
        if (body.isBlank() && mediaIds.isEmpty()) {
            throw validation("EMPTY_POST", "Gönderi metni veya en az bir görsel gerekli.")
        }

        val post = try {
            repository.create(ownerId, body, mediaIds, clock.instant())
        } catch (_: MediaOwnershipException) {
            throw validation("MEDIA_NOT_AVAILABLE", "Görsellerden biri hazır değil veya sana ait değil.", "mediaIds")
        } catch (_: MediaAlreadyAttachedException) {
            throw ApiException(HttpStatusCode.Conflict, "MEDIA_ALREADY_ATTACHED", "Görsellerden biri başka bir gönderide kullanılıyor.")
        }
        val details = repository.findDetails(post.id, ownerId)
            ?: throw ApiException(HttpStatusCode.InternalServerError, "POST_CREATION_FAILED", "Gönderi oluşturulamadı.")
        return response(details)
    }

    fun get(viewerId: UUID, postId: UUID): PostResponse = response(
        repository.findDetails(postId, viewerId)
            ?: throw ApiException(HttpStatusCode.NotFound, "POST_NOT_FOUND", "Gönderi bulunamadı.")
    )

    fun feed(viewerId: UUID, rawCursor: String?, requestedLimit: Int?): FeedResponse {
        val limit = (requestedLimit ?: 20).coerceIn(1, 50)
        val cursor = rawCursor?.let(::decodeCursor)
        val details = repository.feed(viewerId, cursor, limit + 1)
        val hasMore = details.size > limit
        val page = details.take(limit)
        val nextCursor = if (hasMore) page.lastOrNull()?.post?.let(::encodeCursor) else null
        return FeedResponse(page.map(::response), nextCursor)
    }

    fun delete(ownerId: UUID, postId: UUID) {
        if (!repository.markDeleted(postId, ownerId, clock.instant())) {
            throw ApiException(HttpStatusCode.NotFound, "POST_NOT_FOUND", "Gönderi bulunamadı.")
        }
    }

    fun setLike(userId: UUID, postId: UUID, active: Boolean): PostInteractionResponse {
        ensureVisible(postId, userId)
        val count = repository.setLike(postId, userId, active, clock.instant())
        return PostInteractionResponse(postId.toString(), active, count)
    }

    fun setSave(userId: UUID, postId: UUID, active: Boolean): PostInteractionResponse {
        ensureVisible(postId, userId)
        val count = repository.setSave(postId, userId, active, clock.instant())
        return PostInteractionResponse(postId.toString(), active, count)
    }

    private fun ensureVisible(postId: UUID, viewerId: UUID) {
        if (repository.findDetails(postId, viewerId) == null) {
            throw ApiException(HttpStatusCode.NotFound, "POST_NOT_FOUND", "Gönderi bulunamadı.")
        }
    }

    private fun response(details: PostDetails): PostResponse = PostResponse(
        id = details.post.id.toString(),
        text = details.post.body,
        author = details.author,
        media = details.media.map { media ->
            PostMediaResponse(
                id = media.id.toString(),
                mimeType = media.mimeType,
                url = storage.createDownloadUrl(media.storageKey, mediaUrlExpiry),
                urlExpiresInSeconds = mediaUrlExpiry.seconds,
            )
        },
        likeCount = details.likeCount,
        saveCount = details.saveCount,
        likedByMe = details.likedByViewer,
        savedByMe = details.savedByViewer,
        createdAt = details.post.createdAt.toString(),
    )

    private fun encodeCursor(post: Post): String {
        val raw = "${post.createdAt.toEpochMilli()}|${post.id}"
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.toByteArray(StandardCharsets.UTF_8))
    }

    private fun decodeCursor(rawCursor: String): FeedCursor = try {
        val decoded = String(Base64.getUrlDecoder().decode(rawCursor), StandardCharsets.UTF_8)
        val parts = decoded.split('|', limit = 2)
        FeedCursor(Instant.ofEpochMilli(parts[0].toLong()), UUID.fromString(parts[1]))
    } catch (_: Throwable) {
        throw ApiException(HttpStatusCode.BadRequest, "INVALID_CURSOR", "Akış imleci geçersiz.", "cursor")
    }
}
