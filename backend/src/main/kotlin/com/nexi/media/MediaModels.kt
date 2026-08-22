package com.nexi.media

import kotlinx.serialization.Serializable
import java.time.Instant
import java.util.UUID

enum class MediaStatus { PENDING, READY, REJECTED, DELETED }

data class MediaAsset(
    val id: UUID,
    val ownerId: UUID,
    val storageKey: String,
    val originalFilename: String,
    val mimeType: String,
    val declaredSizeBytes: Long,
    val actualSizeBytes: Long?,
    val status: MediaStatus,
    val createdAt: Instant,
    val updatedAt: Instant,
)

@Serializable
data class CreateMediaUploadRequest(
    val filename: String,
    val mimeType: String,
    val sizeBytes: Long,
)

@Serializable
data class CreateMediaUploadResponse(
    val mediaId: String,
    val uploadUrl: String,
    val method: String = "PUT",
    val requiredHeaders: Map<String, String>,
    val expiresInSeconds: Long,
)

@Serializable
data class MediaAssetResponse(
    val id: String,
    val filename: String,
    val mimeType: String,
    val sizeBytes: Long,
    val status: String,
    val downloadUrl: String? = null,
    val downloadUrlExpiresInSeconds: Long? = null,
    val createdAt: String,
)

data class StoredObjectInfo(val sizeBytes: Long, val mimeType: String?, val signatureBytes: ByteArray)
