package com.nexi.moderation

import com.nexi.auth.ApiException
import com.nexi.auth.validation
import com.nexi.media.AvatarUrls
import com.nexi.media.ObjectStorage
import io.ktor.http.HttpStatusCode
import java.nio.charset.StandardCharsets
import java.time.Clock
import java.time.Instant
import java.util.Base64
import java.util.UUID

class ModerationService(
    private val repository: ModerationRepository,
    private val users: UserLookup,
    private val storage: ObjectStorage,
    private val clock: Clock = Clock.systemUTC(),
) {
    /** Kullanıcı adı ↔ kimlik çevirisi. Profil modülüne bağımlı kalmamak için dar bir arayüz. */
    fun interface UserLookup {
        fun idByUsername(username: String): UUID?
    }

    // ------------------------------------------------------------- engelleme

    fun setBlock(blockerId: UUID, username: String, active: Boolean): BlockResponse {
        val targetId = users.idByUsername(username) ?: throw userNotFound()
        if (targetId == blockerId) {
            throw validation("CANNOT_BLOCK_SELF", "Kendini engelleyemezsin.", "username")
        }

        repository.setBlock(blockerId, targetId, active, clock.instant())
        return BlockResponse(username = username, blocked = active)
    }

    fun blockedUsers(blockerId: UUID, rawCursor: String?, requestedLimit: Int?): BlockedUserPageResponse {
        val limit = (requestedLimit ?: DEFAULT_PAGE_SIZE).coerceIn(1, MAX_PAGE_SIZE)
        val rows = repository.blockedUsers(blockerId, rawCursor?.let { decodeBlockCursor(it) }, limit + 1)
        val hasMore = rows.size > limit
        val items = rows.take(limit)

        return BlockedUserPageResponse(
            items = items.map {
                val avatar = AvatarUrls.of(storage, it.avatarStorageKey)
                BlockedUserResponse(
                    id = it.userId.toString(),
                    fullName = it.fullName,
                    username = it.username,
                    avatarUrl = avatar?.url,
                    avatarUrlExpiresInSeconds = avatar?.expiresInSeconds,
                    blockedAt = it.createdAt.toString(),
                )
            },
            nextCursor = if (hasMore) items.lastOrNull()?.let { encodeBlockCursor(it) } else null,
            totalCount = repository.blockedCount(blockerId),
        )
    }

    // --------------------------------------------------------------- şikâyet

    fun report(reporterId: UUID, request: CreateReportRequest): ReportResponse {
        val targetType = enumOrNull<ReportTargetType>(request.targetType)
            ?: throw validation("INVALID_REPORT_TARGET", "Şikâyet hedefi türü geçersiz.", "targetType")
        if (targetType == ReportTargetType.STORY || targetType == ReportTargetType.MESSAGE) {
            throw validation("UNSUPPORTED_REPORT_TARGET", "Bu içerik türü henüz şikâyet edilemiyor.", "targetType")
        }

        val reason = enumOrNull<ReportReason>(request.reason)
            ?: throw validation("INVALID_REPORT_REASON", "Şikâyet nedeni geçersiz.", "reason")

        val targetId = runCatching { UUID.fromString(request.targetId) }.getOrElse {
            throw validation("INVALID_ID", "Şikâyet hedefi kimliği geçersiz.", "targetId")
        }
        if (targetType == ReportTargetType.USER && targetId == reporterId) {
            throw validation("CANNOT_REPORT_SELF", "Kendini şikâyet edemezsin.", "targetId")
        }

        val details = request.details?.trim()?.takeIf { it.isNotEmpty() }
        if (details != null && details.length > MAX_DETAILS_LENGTH) {
            throw validation("DETAILS_TOO_LONG", "Açıklama en fazla $MAX_DETAILS_LENGTH karakter olabilir.", "details")
        }

        if (!repository.targetExists(targetType, targetId)) {
            throw ApiException(HttpStatusCode.NotFound, "REPORT_TARGET_NOT_FOUND", "Şikâyet edilen içerik bulunamadı.")
        }

        val now = clock.instant()
        val (stored, alreadyReported) = repository.createReport(
            Report(
                id = UUID.randomUUID(),
                reporterId = reporterId,
                targetType = targetType,
                targetId = targetId,
                reason = reason,
                details = details,
                status = ReportStatus.OPEN,
                createdAt = now,
                updatedAt = now,
            )
        )
        return stored.toResponse(alreadyReported)
    }

    fun reports(reporterId: UUID, rawCursor: String?, requestedLimit: Int?): ReportPageResponse {
        val limit = (requestedLimit ?: DEFAULT_PAGE_SIZE).coerceIn(1, MAX_PAGE_SIZE)
        val cursor = rawCursor?.let { decodeBlockCursor(it) }
        val rows = repository.reports(reporterId, cursor?.createdAt, cursor?.userId, limit + 1)
        val hasMore = rows.size > limit
        val items = rows.take(limit)

        return ReportPageResponse(
            items = items.map { it.toResponse() },
            nextCursor = if (hasMore) {
                items.lastOrNull()?.let { encodeCursor(it.createdAt, it.id) }
            } else {
                null
            },
            totalCount = repository.reportCount(reporterId),
        )
    }

    private fun userNotFound() =
        ApiException(HttpStatusCode.NotFound, "USER_NOT_FOUND", "Kullanıcı bulunamadı.")

    private inline fun <reified T : Enum<T>> enumOrNull(raw: String): T? =
        enumValues<T>().firstOrNull { it.name.equals(raw.trim(), ignoreCase = true) }

    companion object {
        const val MAX_DETAILS_LENGTH = 1_000
        const val DEFAULT_PAGE_SIZE = 20
        const val MAX_PAGE_SIZE = 50

        internal fun encodeBlockCursor(user: BlockedUser) = encodeCursor(user.createdAt, user.userId)

        internal fun encodeCursor(createdAt: Instant, id: UUID): String {
            val raw = "${createdAt.toEpochMilli()}|$id"
            return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.toByteArray(StandardCharsets.UTF_8))
        }

        internal fun decodeBlockCursor(rawCursor: String): BlockCursor = try {
            val decoded = String(Base64.getUrlDecoder().decode(rawCursor), StandardCharsets.UTF_8)
            val parts = decoded.split('|', limit = 2)
            BlockCursor(Instant.ofEpochMilli(parts[0].toLong()), UUID.fromString(parts[1]))
        } catch (_: Throwable) {
            throw ApiException(HttpStatusCode.BadRequest, "INVALID_CURSOR", "Liste imleci geçersiz.", "cursor")
        }
    }
}
