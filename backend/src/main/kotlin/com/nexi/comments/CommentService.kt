package com.nexi.comments

import com.nexi.auth.ApiException
import com.nexi.auth.validation
import io.ktor.http.HttpStatusCode
import java.nio.charset.StandardCharsets
import java.time.Clock
import java.time.Instant
import java.util.Base64
import java.util.UUID

class CommentService(
    private val repository: CommentRepository,
    private val clock: Clock = Clock.systemUTC(),
) {
    fun create(authorId: UUID, postId: UUID, request: CreateCommentRequest): CommentResponse {
        val body = request.text.trim()
        if (body.isEmpty()) throw validation("EMPTY_COMMENT", "Yorum boş olamaz.", "text")
        if (body.length > MAX_LENGTH) {
            throw validation("COMMENT_TOO_LONG", "Yorum en fazla $MAX_LENGTH karakter olabilir.", "text")
        }

        val now = clock.instant()
        val details = repository.create(
            Comment(
                id = UUID.randomUUID(),
                postId = postId,
                authorId = authorId,
                body = body,
                status = CommentStatus.PUBLISHED,
                createdAt = now,
                updatedAt = now,
            )
        ) ?: throw ApiException(HttpStatusCode.NotFound, "POST_NOT_FOUND", "Gönderi bulunamadı.")

        return details.toResponse(deletableByMe = true)
    }

    fun page(viewerId: UUID, postId: UUID, rawCursor: String?, requestedLimit: Int?): CommentPageResponse {
        val postOwnerId = repository.postOwner(postId)
            ?: throw ApiException(HttpStatusCode.NotFound, "POST_NOT_FOUND", "Gönderi bulunamadı.")

        val limit = (requestedLimit ?: DEFAULT_PAGE_SIZE).coerceIn(1, MAX_PAGE_SIZE)
        val details = repository.page(postId, viewerId, rawCursor?.let { decodeCursor(it) }, limit + 1)
        val hasMore = details.size > limit
        val items = details.take(limit)
        val nextCursor = if (hasMore) items.lastOrNull()?.comment?.let { encodeCursor(it) } else null

        return CommentPageResponse(
            items = items.map { it.toResponse(deletableByMe = canDelete(viewerId, it.comment.authorId, postOwnerId)) },
            nextCursor = nextCursor,
            totalCount = repository.countForPost(postId),
        )
    }

    /**
     * Yorumu sahibi silebilir; gönderi sahibi de kendi gönderisinin altındaki
     * yorumları silebilir. Yetkisiz kullanıcıya yorumun varlığını açık etmemek
     * için 403 yerine 404 dönüyoruz.
     */
    fun delete(viewerId: UUID, commentId: UUID) {
        val comment = repository.findById(commentId)?.takeIf { it.status == CommentStatus.PUBLISHED }
            ?: throw commentNotFound()
        val postOwnerId = repository.postOwner(comment.postId)

        if (!canDelete(viewerId, comment.authorId, postOwnerId)) throw commentNotFound()
        if (!repository.markDeleted(commentId, clock.instant())) throw commentNotFound()
    }

    private fun canDelete(viewerId: UUID, authorId: UUID, postOwnerId: UUID?): Boolean =
        viewerId == authorId || viewerId == postOwnerId

    private fun commentNotFound() =
        ApiException(HttpStatusCode.NotFound, "COMMENT_NOT_FOUND", "Yorum bulunamadı.")

    companion object {
        const val MAX_LENGTH = 1_000
        const val DEFAULT_PAGE_SIZE = 20
        const val MAX_PAGE_SIZE = 50

        internal fun encodeCursor(comment: Comment): String {
            val raw = "${comment.createdAt.toEpochMilli()}|${comment.id}"
            return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.toByteArray(StandardCharsets.UTF_8))
        }

        internal fun decodeCursor(rawCursor: String): CommentCursor = try {
            val decoded = String(Base64.getUrlDecoder().decode(rawCursor), StandardCharsets.UTF_8)
            val parts = decoded.split('|', limit = 2)
            CommentCursor(Instant.ofEpochMilli(parts[0].toLong()), UUID.fromString(parts[1]))
        } catch (_: Throwable) {
            throw ApiException(HttpStatusCode.BadRequest, "INVALID_CURSOR", "Yorum imleci geçersiz.", "cursor")
        }
    }
}
