package com.nexi.stories

import com.nexi.media.MediaAsset
import com.nexi.posts.PostAuthorResponse
import kotlinx.serialization.Serializable
import java.time.Instant
import java.util.UUID

enum class StoryStatus { PUBLISHED, DELETED }

data class Story(
    val id: UUID,
    val ownerId: UUID,
    val mediaId: UUID,
    val caption: String?,
    val status: StoryStatus,
    val publishedAt: Instant,
    val expiresAt: Instant,
)

data class StoryDetails(
    val story: Story,
    val author: PostAuthorResponse,
    val media: MediaAsset,
    val thumbnailStorageKey: String?,
    val viewCount: Long,
    val seenByViewer: Boolean,
)

/** Görüntüleyen listesinin imleci. */
data class StoryViewCursor(val viewedAt: Instant, val viewerId: UUID)

@Serializable
data class CreateStoryRequest(
    val mediaId: String,
    val caption: String? = null,
)

@Serializable
data class StoryMediaResponse(
    val id: String,
    val mimeType: String,
    val url: String,
    val urlExpiresInSeconds: Long,
    /** Yalnızca videolarda dolu. */
    val durationSeconds: Double? = null,
    val width: Int? = null,
    val height: Int? = null,
    val thumbnailUrl: String? = null,
)

@Serializable
data class StoryResponse(
    val id: String,
    val author: PostAuthorResponse,
    val caption: String? = null,
    val media: StoryMediaResponse,
    val publishedAt: String,
    val expiresAt: String,
    val seenByMe: Boolean,
    /** Sayaç yalnızca hikâyenin sahibine gösterilir; başkasında `null`. */
    val viewCount: Long? = null,
)

/**
 * Hikâye akışı yazara göre gruplu döner: arayüz kişi başına tek bir balon
 * gösteriyor, sonra o kişinin hikâyelerini sırayla oynatıyor.
 */
@Serializable
data class StoryGroupResponse(
    val author: PostAuthorResponse,
    val stories: List<StoryResponse>,
    val hasUnseen: Boolean,
    val latestPublishedAt: String,
)

@Serializable
data class StoryFeedResponse(
    val items: List<StoryGroupResponse>,
)

@Serializable
data class StoryViewerResponse(
    val id: String,
    val fullName: String,
    val username: String,
    val avatarUrl: String? = null,
    val avatarUrlExpiresInSeconds: Long? = null,
    val viewedAt: String,
)

@Serializable
data class StoryViewerPageResponse(
    val items: List<StoryViewerResponse>,
    val nextCursor: String? = null,
    val totalCount: Long,
)

@Serializable
data class StoryViewResponse(
    val storyId: String,
    val seen: Boolean,
)

data class StoryViewer(
    val userId: UUID,
    val fullName: String,
    val username: String,
    val avatarStorageKey: String?,
    val viewedAt: Instant,
)
