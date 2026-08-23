package com.nexi.posts

import com.nexi.media.MediaAsset
import com.nexi.topics.Topic
import com.nexi.topics.TopicSummaryResponse
import com.nexi.topics.toSummary
import kotlinx.serialization.Serializable
import java.time.Duration
import java.time.Instant
import java.util.UUID

enum class PostStatus { PUBLISHED, DELETED }

/**
 * Bir gönderinin, kullanıcının ilgi sıralamasına göre hangi katmandan geldiği.
 * Katmanlar dışlayıcıdır: bir gönderi akışta yalnızca tek bir katmana düşer.
 */
enum class FeedTier {
    /**
     * Yazarı görüntüleyen tarafından takip ediliyor. En güçlü sinyal olduğu
     * için konu eşleşmesinden önce gelir: takip ettiğin birinin gönderisi,
     * konusu ne olursa olsun bu katmana düşer.
     */
    FOLLOWING,

    /** Kullanıcının sıralamasında ilk sıralardaki konulardan biri. */
    PRIORITY_TOPIC,

    /** Kullanıcının seçtiği ama alt sıralarda kalan konulardan biri. */
    OTHER_TOPIC,

    /** Seçilen konularla ilişkili (komşu) bir konu. */
    RELATED_TOPIC,

    /** Seçimlerin tamamen dışında kalan keşif içeriği. */
    DISCOVERY,
}

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
    val topics: List<Topic>,
    val likeCount: Long,
    val saveCount: Long,
    val commentCount: Long,
    val likedByViewer: Boolean,
    val savedByViewer: Boolean,
)

@Serializable
data class PostAuthorResponse(
    val id: String,
    val fullName: String,
    val username: String,
    /** Görüntüleyen bu yazarı takip ediyor mu; kartlardaki takip düğmesi bunu okur. */
    val followedByMe: Boolean = false,
)

data class FeedCursor(val createdAt: Instant, val id: UUID)

@Serializable
data class CreatePostRequest(
    val text: String = "",
    val mediaIds: List<String> = emptyList(),
    val topicIds: List<String> = emptyList(),
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
    val topics: List<TopicSummaryResponse>,
    val likeCount: Long,
    val saveCount: Long,
    val commentCount: Long,
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

/** Gönderi cevabı hem klasik akışta hem kişiselleştirilmiş akışta aynı biçimde üretilir. */
internal fun PostDetails.toResponse(
    downloadUrl: (storageKey: String, expiresIn: Duration) -> String,
    mediaUrlExpiry: Duration,
) = PostResponse(
    id = post.id.toString(),
    text = post.body,
    author = author,
    media = media.map { asset ->
        PostMediaResponse(
            id = asset.id.toString(),
            mimeType = asset.mimeType,
            url = downloadUrl(asset.storageKey, mediaUrlExpiry),
            urlExpiresInSeconds = mediaUrlExpiry.seconds,
        )
    },
    topics = topics.map(Topic::toSummary),
    likeCount = likeCount,
    saveCount = saveCount,
    commentCount = commentCount,
    likedByMe = likedByViewer,
    savedByMe = savedByViewer,
    createdAt = post.createdAt.toString(),
)
