package com.nexi.profiles

import com.nexi.auth.ApiException
import com.nexi.auth.validation
import com.nexi.media.AvatarUrls
import com.nexi.media.ObjectStorage
import com.nexi.notifications.NotificationSink
import com.nexi.notifications.NotificationTargetType
import com.nexi.notifications.NotificationType
import io.ktor.http.HttpStatusCode
import java.nio.charset.StandardCharsets
import java.time.Clock
import java.time.Instant
import java.util.Base64
import java.util.UUID

class ProfileService(
    private val repository: ProfileRepository,
    private val storage: ObjectStorage,
    private val clock: Clock = Clock.systemUTC(),
    private val notifications: NotificationSink = NotificationSink.NOOP,
) {
    fun profile(viewerId: UUID, username: String): UserProfileResponse =
        repository.findByUsername(username, viewerId)?.toResponse(storage) ?: throw userNotFound()

    /**
     * Ad ve biyografiyi günceller. Gönderilmeyen alan değişmez; `bio` alanına
     * boş metin göndermek biyografiyi siler.
     */
    fun updateProfile(userId: UUID, username: String, request: UpdateProfileRequest): UserProfileResponse {
        val fullName = request.fullName?.trim()?.replace(Regex("\\s+"), " ")
        if (fullName != null && fullName.length !in 3..120) {
            throw validation("INVALID_FULL_NAME", "Ad soyad 3-120 karakter olmalı.", "fullName")
        }

        val bio = request.bio?.trim()
        if (bio != null && bio.length > MAX_BIO_LENGTH) {
            throw validation("BIO_TOO_LONG", "Biyografi en fazla $MAX_BIO_LENGTH karakter olabilir.", "bio")
        }

        repository.updateProfile(
            userId = userId,
            fullName = fullName,
            bio = bio?.takeIf { it.isNotEmpty() },
            clearBio = bio != null && bio.isEmpty(),
            now = clock.instant(),
        )
        return profile(userId, username)
    }

    fun setAvatar(userId: UUID, username: String, request: SetAvatarRequest): UserProfileResponse {
        val mediaId = runCatching { UUID.fromString(request.mediaId) }.getOrElse {
            throw validation("INVALID_MEDIA_ID", "Görsel kimliği geçersiz.", "mediaId")
        }

        // Sahibi olmadığı bir görseli avatar yapamasın; var olmayanla aynı hatayı
        // veriyoruz ki başkasının görselinin varlığı açığa çıkmasın.
        when (repository.checkAvatarMedia(mediaId, userId)) {
            AvatarMediaCheck.OK -> Unit
            AvatarMediaCheck.NOT_FOUND, AvatarMediaCheck.NOT_OWNED ->
                throw validation("MEDIA_NOT_AVAILABLE", "Görsel bulunamadı veya sana ait değil.", "mediaId")
            AvatarMediaCheck.NOT_READY ->
                throw validation("MEDIA_NOT_READY", "Görsel henüz kullanıma hazır değil.", "mediaId")
            AvatarMediaCheck.NOT_AN_IMAGE ->
                throw validation("AVATAR_MUST_BE_IMAGE", "Avatar olarak yalnızca görsel kullanılabilir.", "mediaId")
        }

        repository.setAvatar(userId, mediaId, clock.instant())
        return profile(userId, username)
    }

    fun removeAvatar(userId: UUID, username: String): UserProfileResponse {
        repository.setAvatar(userId, null, clock.instant())
        return profile(userId, username)
    }

    /** Kullanıcı adını kimliğe çevirir; profil altındaki gönderi listesi bunu kullanıyor. */
    fun userId(username: String): UUID = repository.findIdByUsername(username) ?: throw userNotFound()

    /** Kimliği kullanıcı adına çevirir; `/users/me/...` uçları profili bununla okuyor. */
    fun username(userId: UUID): String = repository.findUsernameById(userId) ?: throw userNotFound()

    /**
     * Takibi açar veya kapatır. Beğeni gibi idempotent: aynı isteği iki kez
     * göndermek hata değil, sonuç aynı kalır.
     */
    fun setFollow(followerId: UUID, username: String, active: Boolean): FollowResponse {
        // Engelli kullanıcı `findByUsername` sorgusundan zaten dönmüyor; takip
        // etmeye çalışmak "böyle bir kullanıcı yok" cevabı alır.
        val target = repository.findByUsername(username, followerId) ?: throw userNotFound()
        if (target.id == followerId) {
            throw validation("CANNOT_FOLLOW_SELF", "Kendini takip edemezsin.", "username")
        }

        val followerCount = repository.setFollow(followerId, target.id, active, clock.instant())
        // Takibi bırakmak bildirim üretmez; yalnızca yeni takip.
        if (active) {
            notifications.emit(target.id, followerId, NotificationType.FOLLOW, NotificationTargetType.USER, followerId)
        }
        return FollowResponse(username = target.username, following = active, followerCount = followerCount)
    }

    fun followers(viewerId: UUID, username: String, rawCursor: String?, requestedLimit: Int?): UserPageResponse {
        val userId = repository.findIdByUsername(username) ?: throw userNotFound()
        return page(
            viewerId = viewerId,
            rawCursor = rawCursor,
            requestedLimit = requestedLimit,
            total = repository.followerCount(userId),
        ) { cursor, size -> repository.followers(userId, viewerId, cursor, size) }
    }

    fun following(viewerId: UUID, username: String, rawCursor: String?, requestedLimit: Int?): UserPageResponse {
        val userId = repository.findIdByUsername(username) ?: throw userNotFound()
        return page(
            viewerId = viewerId,
            rawCursor = rawCursor,
            requestedLimit = requestedLimit,
            total = repository.followingCount(userId),
        ) { cursor, size -> repository.following(userId, viewerId, cursor, size) }
    }

    private fun page(
        viewerId: UUID,
        rawCursor: String?,
        requestedLimit: Int?,
        total: Long,
        load: (FollowCursor?, Int) -> List<FollowEdge>,
    ): UserPageResponse {
        val limit = (requestedLimit ?: DEFAULT_PAGE_SIZE).coerceIn(1, MAX_PAGE_SIZE)
        val edges = load(rawCursor?.let { decodeCursor(it) }, limit + 1)
        val hasMore = edges.size > limit
        val items = edges.take(limit)

        return UserPageResponse(
            items = items.map {
                val avatar = AvatarUrls.of(storage, it.avatarStorageKey)
                UserSummaryResponse(
                    id = it.userId.toString(),
                    fullName = it.fullName,
                    username = it.username,
                    avatarUrl = avatar?.url,
                    avatarUrlExpiresInSeconds = avatar?.expiresInSeconds,
                    followedByMe = it.followedByViewer,
                    isMe = it.userId == viewerId,
                )
            },
            nextCursor = if (hasMore) items.lastOrNull()?.let { encodeCursor(it) } else null,
            totalCount = total,
        )
    }

    private fun userNotFound() =
        ApiException(HttpStatusCode.NotFound, "USER_NOT_FOUND", "Kullanıcı bulunamadı.")

    companion object {
        const val MAX_BIO_LENGTH = 280
        const val DEFAULT_PAGE_SIZE = 20
        const val MAX_PAGE_SIZE = 50

        internal fun encodeCursor(edge: FollowEdge): String {
            val raw = "${edge.createdAt.toEpochMilli()}|${edge.userId}"
            return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.toByteArray(StandardCharsets.UTF_8))
        }

        internal fun decodeCursor(rawCursor: String): FollowCursor = try {
            val decoded = String(Base64.getUrlDecoder().decode(rawCursor), StandardCharsets.UTF_8)
            val parts = decoded.split('|', limit = 2)
            FollowCursor(Instant.ofEpochMilli(parts[0].toLong()), UUID.fromString(parts[1]))
        } catch (_: Throwable) {
            throw ApiException(HttpStatusCode.BadRequest, "INVALID_CURSOR", "Liste imleci geçersiz.", "cursor")
        }
    }
}
