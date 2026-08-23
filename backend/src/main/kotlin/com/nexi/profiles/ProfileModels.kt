package com.nexi.profiles

import kotlinx.serialization.Serializable
import java.time.Instant
import java.util.UUID

/** Herkese açık profil; e-posta gibi özel alanlar burada yer almaz. */
data class UserProfile(
    val id: UUID,
    val fullName: String,
    val username: String,
    val createdAt: Instant,
    val postCount: Long,
    val followerCount: Long,
    val followingCount: Long,
    val followedByViewer: Boolean,
    val isViewer: Boolean,
)

/** Takipçi ve takip listelerinin imleci. */
data class FollowCursor(val createdAt: Instant, val userId: UUID)

@Serializable
data class UserProfileResponse(
    val id: String,
    val fullName: String,
    val username: String,
    val createdAt: String,
    val postCount: Long,
    val followerCount: Long,
    val followingCount: Long,
    val followedByMe: Boolean,
    /** Kendi profilin: takip düğmesi yerine düzenleme gösterilir. */
    val isMe: Boolean,
)

@Serializable
data class UserSummaryResponse(
    val id: String,
    val fullName: String,
    val username: String,
    val followedByMe: Boolean,
    val isMe: Boolean,
)

@Serializable
data class UserPageResponse(
    val items: List<UserSummaryResponse>,
    val nextCursor: String? = null,
    val totalCount: Long,
)

@Serializable
data class FollowResponse(
    val username: String,
    val following: Boolean,
    val followerCount: Long,
)

internal fun UserProfile.toResponse() = UserProfileResponse(
    id = id.toString(),
    fullName = fullName,
    username = username,
    createdAt = createdAt.toString(),
    postCount = postCount,
    followerCount = followerCount,
    followingCount = followingCount,
    followedByMe = followedByViewer,
    isMe = isViewer,
)
