package com.nexi.media

import kotlinx.serialization.Serializable
import java.time.Instant
import java.util.UUID

/** Yüklemenin kabul edilip edilmediği. */
enum class MediaStatus { PENDING, READY, REJECTED, DELETED }

/**
 * İçeriğin oynatılabilir olup olmadığı. Bugün işleme kuyruğu olmadığı için
 * `UPLOADED` ve `PROCESSING` durumlarında kimse beklemiyor; kuyruk eklendiğinde
 * video bu iki durumda kalabilecek ve akış onu göstermeyecek.
 */
enum class MediaProcessingStatus { PENDING_UPLOAD, UPLOADED, PROCESSING, READY, FAILED }

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
    val processingStatus: MediaProcessingStatus = MediaProcessingStatus.PENDING_UPLOAD,
    val failureReason: String? = null,
    /** Yalnızca videolarda dolu. */
    val durationSeconds: Double? = null,
    val width: Int? = null,
    val height: Int? = null,
    val thumbnailMediaId: UUID? = null,
) {
    val isVideo: Boolean get() = mimeType.startsWith("video/")
}

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

/** Tamamlama isteği; kapak görseli isteğe bağlı ve yalnızca videolarda anlamlı. */
@Serializable
data class CompleteMediaUploadRequest(
    val thumbnailMediaId: String? = null,
)

@Serializable
data class MediaAssetResponse(
    val id: String,
    val filename: String,
    val mimeType: String,
    val sizeBytes: Long,
    val status: String,
    val processingStatus: String,
    val failureReason: String? = null,
    val downloadUrl: String? = null,
    val downloadUrlExpiresInSeconds: Long? = null,
    val durationSeconds: Double? = null,
    val width: Int? = null,
    val height: Int? = null,
    val thumbnailUrl: String? = null,
    val thumbnailUrlExpiresInSeconds: Long? = null,
    val createdAt: String,
)

/** `GET /media/{id}/status` için hafif cevap; istemci oynatılabilirliği bununla yokluyor. */
@Serializable
data class MediaStatusResponse(
    val id: String,
    val status: String,
    val processingStatus: String,
    val playable: Boolean,
    val failureReason: String? = null,
    val durationSeconds: Double? = null,
    val width: Int? = null,
    val height: Int? = null,
)

data class StoredObjectInfo(val sizeBytes: Long, val mimeType: String?, val signatureBytes: ByteArray)
