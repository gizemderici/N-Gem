package com.nexi.posts

import com.nexi.media.MediaAsset
import kotlinx.serialization.Serializable
import java.time.Instant
import java.util.UUID

enum class PostStatus { PUBLISHED, DELETED }

data class Post(
    val id: UUID,
    val ownerId: UUID,
    val body: String,
    val status: PostStatus,
    val createdAt: Instant,
    val updatedAt: Instant,
)

data class PostDetails(
    val post: Post,
    val author: PostAuthorResponse,
    val media: List<MediaAsset>,
    val likeCount: Long,
    val saveCount: Long,
    val likedByViewer: Boolean,
    val savedByViewer: Boolean,
)

@Serializable
data class PostAuthorResponse(
    val id: String,
    val fullName: String,
    val username: String,
)

data class FeedCursor(val createdAt: Instant, val id: UUID)

@Serializable
data class CreatePostRequest(
    val text: String = "",
    val mediaIds: List<String> = emptyList(),
)

@Serializable
data class PostMediaResponse(
    val id: String,
    val mimeType: String,
    val width: Int? = null,
    val height: Int? = null,
    val url: String,
    val urlExpiresInSeconds: Long,
)

@Serializable
data class PostResponse(
    val id: String,
    val text: String,
    val author: PostAuthorResponse,
    val media: List<PostMediaResponse>,
    val likeCount: Long,
    val saveCount: Long,
    val likedByMe: Boolean,
    val savedByMe: Boolean,
    val createdAt: String,
    val recommendationReason: String? = null,
)

@Serializable
data class FeedResponse(
    val items: List<PostResponse>,
    val nextCursor: String? = null,
    val requestId: String? = null,
    val modelVersion: String? = null,
)

@Serializable
data class PostInteractionResponse(
    val postId: String,
    val active: Boolean,
    val count: Long,
)
