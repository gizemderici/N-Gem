package com.nexi.profiles

import com.nexi.media.AvatarUrls
import com.nexi.media.ObjectStorage
import kotlinx.serialization.Serializable
import java.time.Instant
import java.util.UUID

/** Herkese açık profil; e-posta gibi özel alanlar burada yer almaz. */
data class UserProfile(
    val id: UUID,
    val fullName: String,
    val username: String,
    val bio: String?,
    /** Depolama anahtarı sunucuda kalır; imzalı adres servis katmanında üretilir. */
    val avatarStorageKey: String?,
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
    val bio: String? = null,
    val avatarUrl: String? = null,
    val avatarUrlExpiresInSeconds: Long? = null,
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
    val avatarUrl: String? = null,
    val avatarUrlExpiresInSeconds: Long? = null,
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

/**
 * Profil düzenleme. Alanlar isteğe bağlı: gönderilmeyen alan değişmez.
 * `bio` için boş metin göndermek biyografiyi siler.
 */
@Serializable
data class UpdateProfileRequest(
    val fullName: String? = null,
    val bio: String? = null,
)

@Serializable
data class SetAvatarRequest(val mediaId: String)

internal fun UserProfile.toResponse(storage: ObjectStorage): UserProfileResponse {
    val avatar = AvatarUrls.of(storage, avatarStorageKey)
    return UserProfileResponse(
        id = id.toString(),
        fullName = fullName,
        username = username,
        bio = bio,
        avatarUrl = avatar?.url,
        avatarUrlExpiresInSeconds = avatar?.expiresInSeconds,
        createdAt = createdAt.toString(),
        postCount = postCount,
        followerCount = followerCount,
        followingCount = followingCount,
        followedByMe = followedByViewer,
        isMe = isViewer,
    )
}
