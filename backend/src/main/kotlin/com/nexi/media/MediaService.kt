package com.nexi.media

import com.nexi.auth.ApiException
import com.nexi.auth.validation
import com.nexi.config.StorageConfig
import io.ktor.http.HttpStatusCode
import java.time.Clock
import java.time.Duration
import java.util.UUID

class MediaService(
    private val repository: MediaRepository,
    private val storage: ObjectStorage,
    private val config: StorageConfig,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val uploadExpiry = Duration.ofMinutes(10)
    private val downloadExpiry = Duration.ofMinutes(15)
    private val supportedTypes = mapOf(
        "image/jpeg" to "jpg",
        "image/png" to "png",
        "image/webp" to "webp",
        "video/mp4" to "mp4",
    )

    fun createUpload(ownerId: UUID, request: CreateMediaUploadRequest): CreateMediaUploadResponse {
        val filename = request.filename.trim()
        val mimeType = request.mimeType.trim().lowercase()
        val extension = supportedTypes[mimeType]
            ?: throw validation("UNSUPPORTED_MEDIA_TYPE", "Yalnızca JPEG, PNG, WebP görseller ve MP4 videolar yüklenebilir.", "mimeType")
        if (filename.isBlank() || filename.length > 255) {
            throw validation("INVALID_FILENAME", "Dosya adı 1-255 karakter olmalı.", "filename")
        }
        val maxSizeBytes = maxSizeFor(mimeType)
        if (request.sizeBytes !in 1..maxSizeBytes) {
            throw validation(
                "INVALID_MEDIA_SIZE",
                "Medya boyutu 1 byte ile $maxSizeBytes byte arasında olmalı.",
                "sizeBytes",
            )
        }

        val now = clock.instant()
        val id = UUID.randomUUID()
        val storageKey = "users/$ownerId/media/$id.$extension"
        repository.create(
            MediaAsset(
                id = id,
                ownerId = ownerId,
                storageKey = storageKey,
                originalFilename = filename,
                mimeType = mimeType,
                declaredSizeBytes = request.sizeBytes,
                actualSizeBytes = null,
                status = MediaStatus.PENDING,
                createdAt = now,
                updatedAt = now,
            )
        )

        return try {
            CreateMediaUploadResponse(
                mediaId = id.toString(),
                uploadUrl = storage.createUploadUrl(storageKey, mimeType, uploadExpiry),
                requiredHeaders = mapOf("Content-Type" to mimeType),
                expiresInSeconds = uploadExpiry.seconds,
            )
        } catch (exception: Throwable) {
            repository.markStatus(id, MediaStatus.REJECTED, clock.instant())
            throw exception
        }
    }

    fun completeUpload(ownerId: UUID, mediaId: UUID): MediaAssetResponse {
        val asset = ownedAsset(ownerId, mediaId)
        if (asset.status == MediaStatus.READY) return response(asset, includeDownloadUrl = true)
        if (asset.status != MediaStatus.PENDING) {
            throw ApiException(HttpStatusCode.Conflict, "MEDIA_NOT_PENDING", "Bu medya yükleme için uygun durumda değil.")
        }

        val objectInfo = try {
            storage.inspect(asset.storageKey)
        } catch (_: software.amazon.awssdk.services.s3.model.NoSuchKeyException) {
            throw ApiException(HttpStatusCode.Conflict, "UPLOAD_NOT_FOUND", "Medya henüz depolamaya yüklenmemiş.")
        }

        val invalidReason = validateStoredObject(asset, objectInfo)
        if (invalidReason != null) {
            storage.delete(asset.storageKey)
            repository.markStatus(asset.id, MediaStatus.REJECTED, clock.instant())
            throw validation("INVALID_UPLOADED_FILE", invalidReason)
        }

        val now = clock.instant()
        if (!repository.markReady(asset.id, objectInfo.sizeBytes, now)) {
            throw ApiException(HttpStatusCode.Conflict, "MEDIA_STATE_CHANGED", "Medya durumu değişti; tekrar kontrol et.")
        }
        return response(asset.copy(actualSizeBytes = objectInfo.sizeBytes, status = MediaStatus.READY, updatedAt = now), true)
    }

    fun get(ownerId: UUID, mediaId: UUID): MediaAssetResponse {
        val asset = ownedAsset(ownerId, mediaId)
        if (asset.status != MediaStatus.READY) {
            throw ApiException(HttpStatusCode.Conflict, "MEDIA_NOT_READY", "Medya henüz kullanıma hazır değil.")
        }
        return response(asset, includeDownloadUrl = true)
    }

    fun delete(ownerId: UUID, mediaId: UUID) {
        val asset = ownedAsset(ownerId, mediaId)
        if (asset.status == MediaStatus.DELETED) return
        storage.delete(asset.storageKey)
        repository.markStatus(asset.id, MediaStatus.DELETED, clock.instant())
    }

    private fun ownedAsset(ownerId: UUID, mediaId: UUID): MediaAsset {
        val asset = repository.findById(mediaId)
            ?: throw ApiException(HttpStatusCode.NotFound, "MEDIA_NOT_FOUND", "Medya bulunamadı.")
        if (asset.ownerId != ownerId) {
            // Varlığın başka kullanıcıya ait olduğunu açığa çıkarmıyoruz.
            throw ApiException(HttpStatusCode.NotFound, "MEDIA_NOT_FOUND", "Medya bulunamadı.")
        }
        return asset
    }

    private fun validateStoredObject(asset: MediaAsset, info: StoredObjectInfo): String? {
        if (info.sizeBytes != asset.declaredSizeBytes) return "Yüklenen dosyanın boyutu bildirilen değerle eşleşmiyor."
        if (info.sizeBytes !in 1..maxSizeFor(asset.mimeType)) return "Yüklenen dosya izin verilen boyutu aşıyor."
        if (info.mimeType?.substringBefore(';')?.trim()?.lowercase() != asset.mimeType) {
            return "Yüklenen dosyanın içerik türü eşleşmiyor."
        }
        if (!signatureMatches(asset.mimeType, info.signatureBytes)) return "Dosya içeriği geçerli bir medya değil."
        return null
    }

    private fun signatureMatches(mimeType: String, bytes: ByteArray): Boolean = when (mimeType) {
        "image/jpeg" -> bytes.startsWith(0xFF, 0xD8, 0xFF)
        "image/png" -> bytes.startsWith(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        "image/webp" -> bytes.startsWithAscii("RIFF") && bytes.drop(8).toByteArray().startsWithAscii("WEBP")
        "video/mp4" -> bytes.size >= 12 && bytes.copyOfRange(4, 8).startsWithAscii("ftyp")
        else -> false
    }

    private fun maxSizeFor(mimeType: String): Long =
        if (mimeType.startsWith("video/")) config.maxVideoSizeBytes else config.maxImageSizeBytes

    private fun response(asset: MediaAsset, includeDownloadUrl: Boolean): MediaAssetResponse {
        val url = if (includeDownloadUrl) storage.createDownloadUrl(asset.storageKey, downloadExpiry) else null
        return MediaAssetResponse(
            id = asset.id.toString(),
            filename = asset.originalFilename,
            mimeType = asset.mimeType,
            sizeBytes = asset.actualSizeBytes ?: asset.declaredSizeBytes,
            status = asset.status.name,
            downloadUrl = url,
            downloadUrlExpiresInSeconds = if (url == null) null else downloadExpiry.seconds,
            createdAt = asset.createdAt.toString(),
        )
    }
}

private fun ByteArray.startsWith(vararg expected: Int): Boolean =
    size >= expected.size && expected.indices.all { this[it].toInt() and 0xFF == expected[it] }

private fun ByteArray.startsWithAscii(value: String): Boolean =
    startsWith(*value.toByteArray(Charsets.US_ASCII).map { it.toInt() and 0xFF }.toIntArray())
