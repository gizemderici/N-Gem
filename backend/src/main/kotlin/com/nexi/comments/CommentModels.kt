package com.nexi.comments

import com.nexi.media.ObjectStorage
import com.nexi.posts.PostAuthorResponse
import com.nexi.posts.withAvatar
import kotlinx.serialization.Serializable
import java.time.Instant
import java.util.UUID

enum class CommentStatus { PUBLISHED, DELETED }

data class Comment(
    val id: UUID,
    val postId: UUID,
    val authorId: UUID,
    val body: String,
    val status: CommentStatus,
    val createdAt: Instant,
    val updatedAt: Instant,
)

data class CommentDetails(
    val comment: Comment,
    val author: PostAuthorResponse,
)

/** Yorum sayfalamasının imleci. Gönderi akışının aksine eskiden yeniye ilerler. */
data class CommentCursor(val createdAt: Instant, val id: UUID)

@Serializable
data class CreateCommentRequest(val text: String = "")

@Serializable
data class CommentResponse(
    val id: String,
    val postId: String,
    val text: String,
    val author: PostAuthorResponse,
    val createdAt: String,
    /** Görüntüleyen bu yorumu silebilir mi: yorumun sahibi ya da gönderinin sahibi. */
    val deletableByMe: Boolean,
)

@Serializable
data class CommentPageResponse(
    val items: List<CommentResponse>,
    val nextCursor: String? = null,
    val totalCount: Long,
)

internal fun CommentDetails.toResponse(storage: ObjectStorage, deletableByMe: Boolean) = CommentResponse(
    id = comment.id.toString(),
    postId = comment.postId.toString(),
    text = comment.body,
    author = author.withAvatar(storage),
    createdAt = comment.createdAt.toString(),
    deletableByMe = deletableByMe,
)
