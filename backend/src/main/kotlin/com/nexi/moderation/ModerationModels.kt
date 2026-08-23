package com.nexi.moderation

import kotlinx.serialization.Serializable
import java.time.Instant
import java.util.UUID

enum class ReportTargetType { USER, POST, COMMENT, STORY, MESSAGE }

enum class ReportStatus { OPEN, REVIEWING, ACTIONED, DISMISSED }

enum class ReportReason { SPAM, HARASSMENT, HATE_SPEECH, VIOLENCE, NUDITY, SELF_HARM, MISINFORMATION, OTHER }

data class Report(
    val id: UUID,
    val reporterId: UUID,
    val targetType: ReportTargetType,
    val targetId: UUID,
    val reason: ReportReason,
    val details: String?,
    val status: ReportStatus,
    val createdAt: Instant,
    val updatedAt: Instant,
)

data class BlockedUser(
    val userId: UUID,
    val fullName: String,
    val username: String,
    val avatarStorageKey: String?,
    val createdAt: Instant,
)

/** Engellenenler listesinin imleci. */
data class BlockCursor(val createdAt: Instant, val userId: UUID)

@Serializable
data class BlockResponse(
    val username: String,
    val blocked: Boolean,
)

@Serializable
data class BlockedUserResponse(
    val id: String,
    val fullName: String,
    val username: String,
    val avatarUrl: String? = null,
    val avatarUrlExpiresInSeconds: Long? = null,
    val blockedAt: String,
)

@Serializable
data class BlockedUserPageResponse(
    val items: List<BlockedUserResponse>,
    val nextCursor: String? = null,
    val totalCount: Long,
)

@Serializable
data class CreateReportRequest(
    val targetType: String,
    val targetId: String,
    val reason: String,
    val details: String? = null,
)

@Serializable
data class ReportResponse(
    val id: String,
    val targetType: String,
    val targetId: String,
    val reason: String,
    val details: String? = null,
    val status: String,
    val createdAt: String,
    /** İkinci kez şikâyet edildiğinde mevcut kayıt döner; istemci bunu ayırt edebilsin. */
    val alreadyReported: Boolean = false,
)

@Serializable
data class ReportPageResponse(
    val items: List<ReportResponse>,
    val nextCursor: String? = null,
    val totalCount: Long,
)

internal fun Report.toResponse(alreadyReported: Boolean = false) = ReportResponse(
    id = id.toString(),
    targetType = targetType.name,
    targetId = targetId.toString(),
    reason = reason.name,
    details = details,
    status = status.name,
    createdAt = createdAt.toString(),
    alreadyReported = alreadyReported,
)
