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

    fun completeUpload(
        ownerId: UUID,
        mediaId: UUID,
        request: CompleteMediaUploadRequest = CompleteMediaUploadRequest(),
    ): MediaAssetResponse {
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

        validateStoredObject(asset, objectInfo)?.let { reject(asset, it) }

        // Kapak görseli yalnızca videoda anlamlı; sahiplik ve tür kontrolü avatarla aynı.
        val thumbnailId = request.thumbnailMediaId?.let { raw ->
            if (!asset.isVideo) throw validation("THUMBNAIL_NOT_ALLOWED", "Kapak görseli yalnızca videolara eklenebilir.", "thumbnailMediaId")
            resolveThumbnail(ownerId, raw)
        }

        val metadata = if (asset.isVideo) {
            val parsed = readVideoMetadata(asset) ?: reject(asset, "Video meta verisi okunamadı; dosya bozuk olabilir.")
            validateVideo(parsed)?.let { reject(asset, it) }
            parsed
        } else {
            null
        }

        val now = clock.instant()
        if (!repository.markReady(asset.id, objectInfo.sizeBytes, now)) {
            throw ApiException(HttpStatusCode.Conflict, "MEDIA_STATE_CHANGED", "Medya durumu değişti; tekrar kontrol et.")
        }
        if (metadata != null || thumbnailId != null) {
            repository.saveVideoMetadata(asset.id, metadata?.durationSeconds, metadata?.width, metadata?.height, thumbnailId, now)
        }
        // İşleme kuyruğu olmadığı için doğrulamayı geçen içerik doğrudan oynatılabilir.
        repository.markProcessing(asset.id, MediaProcessingStatus.READY, null, now)

        return response(
            asset.copy(
                actualSizeBytes = objectInfo.sizeBytes,
                status = MediaStatus.READY,
                processingStatus = MediaProcessingStatus.READY,
                durationSeconds = metadata?.durationSeconds,
                width = metadata?.width,
                height = metadata?.height,
                thumbnailMediaId = thumbnailId,
                updatedAt = now,
            ),
            includeDownloadUrl = true,
        )
    }

    fun status(ownerId: UUID, mediaId: UUID): MediaStatusResponse {
        val asset = ownedAsset(ownerId, mediaId)
        return MediaStatusResponse(
            id = asset.id.toString(),
            status = asset.status.name,
            processingStatus = asset.processingStatus.name,
            playable = asset.status == MediaStatus.READY && asset.processingStatus == MediaProcessingStatus.READY,
            failureReason = asset.failureReason,
            durationSeconds = asset.durationSeconds,
            width = asset.width,
            height = asset.height,
        )
    }

    /** Reddedilen yükleme depodan silinir ve nedeni kaydedilir. */
    private fun reject(asset: MediaAsset, reason: String): Nothing {
        val now = clock.instant()
        runCatching { storage.delete(asset.storageKey) }
        repository.markStatus(asset.id, MediaStatus.REJECTED, now)
        repository.markProcessing(asset.id, MediaProcessingStatus.FAILED, reason, now)
        throw validation("INVALID_UPLOADED_FILE", reason)
    }

    private fun resolveThumbnail(ownerId: UUID, rawId: String): UUID {
        val id = runCatching { UUID.fromString(rawId) }.getOrElse {
            throw validation("INVALID_MEDIA_ID", "Kapak görseli kimliği geçersiz.", "thumbnailMediaId")
        }
        val thumbnail = repository.findById(id)
        // Başkasının görselinin varlığını açığa çıkarmamak için "yok" ile aynı hata.
        if (thumbnail == null || thumbnail.ownerId != ownerId) {
            throw validation("MEDIA_NOT_AVAILABLE", "Kapak görseli bulunamadı veya sana ait değil.", "thumbnailMediaId")
        }
        if (thumbnail.status != MediaStatus.READY) {
            throw validation("MEDIA_NOT_READY", "Kapak görseli henüz hazır değil.", "thumbnailMediaId")
        }
        if (!thumbnail.mimeType.startsWith("image/")) {
            throw validation("THUMBNAIL_MUST_BE_IMAGE", "Kapak görseli bir görsel olmalı.", "thumbnailMediaId")
        }
        return id
    }

    /**
     * `moov` kutusu dosyanın başında da sonunda da olabilir (`faststart`
     * uygulanmamış dosyalarda sondadır), bu yüzden önce baş, sonra son okunuyor.
     */
    private fun readVideoMetadata(asset: MediaAsset): Mp4Metadata? {
        val size = asset.actualSizeBytes ?: asset.declaredSizeBytes
        val window = Mp4Parser.SCAN_WINDOW_BYTES.toLong()

        val head = runCatching { storage.readRange(asset.storageKey, 0, minOf(window, size) - 1) }.getOrNull()
        Mp4Parser.parse(head ?: ByteArray(0))?.let { return it }

        if (size <= window) return null
        val tail = runCatching { storage.readRange(asset.storageKey, size - window, size - 1) }.getOrNull()
        return Mp4Parser.parse(tail ?: ByteArray(0))
    }

    private fun validateVideo(metadata: Mp4Metadata): String? {
        if (metadata.durationSeconds <= 0) return "Video süresi okunamadı."
        if (metadata.durationSeconds > config.maxVideoDurationSeconds) {
            return "Video en fazla ${config.maxVideoDurationSeconds} saniye olabilir."
        }
        val pixels = metadata.width.toLong() * metadata.height.toLong()
        if (pixels > config.maxVideoPixels) return "Video çözünürlüğü izin verilen sınırın üzerinde."
        return null
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
        // Kapak görselinin adresi ayrı bir kayıttan geliyor; yoksa alan boş kalır.
        val thumbnailKey = asset.thumbnailMediaId
            ?.let { repository.findById(it) }
            ?.takeIf { it.status == MediaStatus.READY }
            ?.storageKey
        val thumbnailUrl = thumbnailKey?.let { storage.createDownloadUrl(it, downloadExpiry) }

        return MediaAssetResponse(
            id = asset.id.toString(),
            filename = asset.originalFilename,
            mimeType = asset.mimeType,
            sizeBytes = asset.actualSizeBytes ?: asset.declaredSizeBytes,
            status = asset.status.name,
            processingStatus = asset.processingStatus.name,
            failureReason = asset.failureReason,
            downloadUrl = url,
            downloadUrlExpiresInSeconds = if (url == null) null else downloadExpiry.seconds,
            durationSeconds = asset.durationSeconds,
            width = asset.width,
            height = asset.height,
            thumbnailUrl = thumbnailUrl,
            thumbnailUrlExpiresInSeconds = if (thumbnailUrl == null) null else downloadExpiry.seconds,
            createdAt = asset.createdAt.toString(),
        )
    }
}

private fun ByteArray.startsWith(vararg expected: Int): Boolean =
    size >= expected.size && expected.indices.all { this[it].toInt() and 0xFF == expected[it] }

private fun ByteArray.startsWithAscii(value: String): Boolean =
    startsWith(*value.toByteArray(Charsets.US_ASCII).map { it.toInt() and 0xFF }.toIntArray())
