package com.nexi.profiles

import com.nexi.auth.ApiException
import com.nexi.auth.validation
import io.ktor.http.HttpStatusCode
import java.nio.charset.StandardCharsets
import java.time.Clock
import java.time.Instant
import java.util.Base64
import java.util.UUID

class ProfileService(
    private val repository: ProfileRepository,
    private val clock: Clock = Clock.systemUTC(),
) {
    fun profile(viewerId: UUID, username: String): UserProfileResponse =
        repository.findByUsername(username, viewerId)?.toResponse() ?: throw userNotFound()

    /** Kullanıcı adını kimliğe çevirir; profil altındaki gönderi listesi bunu kullanıyor. */
    fun userId(username: String): UUID = repository.findIdByUsername(username) ?: throw userNotFound()

    /**
     * Takibi açar veya kapatır. Beğeni gibi idempotent: aynı isteği iki kez
     * göndermek hata değil, sonuç aynı kalır.
     */
    fun setFollow(followerId: UUID, username: String, active: Boolean): FollowResponse {
        val target = repository.findByUsername(username, followerId) ?: throw userNotFound()
        if (target.id == followerId) {
            throw validation("CANNOT_FOLLOW_SELF", "Kendini takip edemezsin.", "username")
        }

        val followerCount = repository.setFollow(followerId, target.id, active, clock.instant())
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
                UserSummaryResponse(
                    id = it.userId.toString(),
                    fullName = it.fullName,
                    username = it.username,
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
