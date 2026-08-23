package com.nexi.media

import com.nexi.auth.ApiException
import com.nexi.config.StorageConfig
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VideoUploadTest {
    private val clock = Clock.fixed(Instant.parse("2026-08-23T00:00:00Z"), ZoneOffset.UTC)
    private val repository = InMemoryMediaRepository()
    private val storage = FakeObjectStorage()
    private val config = StorageConfig(
        endpoint = "http://storage",
        publicEndpoint = "http://public-storage",
        region = "us-east-1",
        accessKey = "test",
        secretKey = "test",
        bucket = "test",
        maxImageSizeBytes = 10 * 1024 * 1024,
        maxVideoSizeBytes = 25 * 1024 * 1024,
        maxVideoDurationSeconds = 60,
        maxVideoPixels = 1920L * 1080L,
    )
    private val service = MediaService(repository, storage, config, clock)
    private val ownerId = UUID.randomUUID()

    /** Yukleme baslatir ve dosyayi depoya koyar; tamamlama cagirmaz. */
    private fun stage(bytes: ByteArray, mimeType: String, filename: String): MediaAsset {
        val upload = service.createUpload(ownerId, CreateMediaUploadRequest(filename, mimeType, bytes.size.toLong()))
        val asset = assertNotNull(repository.findById(UUID.fromString(upload.mediaId)))
        storage.objects[asset.storageKey] = StoredObjectInfo(bytes.size.toLong(), mimeType, bytes.copyOfRange(0, minOf(32, bytes.size)))
        storage.contents[asset.storageKey] = bytes
        return asset
    }

    private fun stageReadyImage(): UUID {
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0)
        val asset = stage(png, "image/png", "kapak.png")
        service.completeUpload(ownerId, asset.id)
        return asset.id
    }

    @Test
    fun `a valid video is accepted and its metadata is stored`() {
        val asset = stage(Mp4Fixtures.mp4(durationSeconds = 12.0, width = 1280, height = 720), "video/mp4", "demo.mp4")

        val completed = service.completeUpload(ownerId, asset.id)

        assertEquals("READY", completed.status)
        assertEquals("READY", completed.processingStatus)
        assertEquals(12.0, assertNotNull(completed.durationSeconds), 0.001)
        assertEquals(1280, completed.width)
        assertEquals(720, completed.height)
    }

    @Test
    fun `an overlong video is rejected and removed from storage`() {
        val asset = stage(Mp4Fixtures.mp4(durationSeconds = 120.0), "video/mp4", "uzun.mp4")

        val error = assertFailsWith { service.completeUpload(ownerId, asset.id) }

        assertEquals("INVALID_UPLOADED_FILE", error.code)
        assertTrue(error.message.contains("60"), "mesaj siniri soylemeli: ${error.message}")
        assertEquals(MediaStatus.REJECTED, repository.findById(asset.id)?.status)
        assertEquals(MediaProcessingStatus.FAILED, repository.findById(asset.id)?.processingStatus)
        assertTrue(asset.storageKey in storage.deletedKeys)
    }

    @Test
    fun `a video above the pixel budget is rejected`() {
        val asset = stage(Mp4Fixtures.mp4(width = 3840, height = 2160), "video/mp4", "4k.mp4")

        assertEquals("INVALID_UPLOADED_FILE", assertFailsWith { service.completeUpload(ownerId, asset.id) }.code)
    }

    @Test
    fun `a video whose metadata cannot be read is rejected`() {
        // Imzasi dogru ama moov kutusu yok: bozuk ya da desteklenmeyen dosya.
        val asset = stage(Mp4Fixtures.signatureOnly(), "video/mp4", "bozuk.mp4")

        val error = assertFailsWith { service.completeUpload(ownerId, asset.id) }

        assertEquals("INVALID_UPLOADED_FILE", error.code)
        assertEquals(MediaProcessingStatus.FAILED, repository.findById(asset.id)?.processingStatus)
        assertNotNull(repository.findById(asset.id)?.failureReason)
    }

    @Test
    fun `moov at the end of the file is still found`() {
        val asset = stage(
            Mp4Fixtures.mp4(durationSeconds = 8.0, moovAtEnd = true, paddingBytes = 64 * 1024),
            "video/mp4",
            "faststart-degil.mp4",
        )

        assertEquals(8.0, assertNotNull(service.completeUpload(ownerId, asset.id).durationSeconds), 0.001)
    }

    @Test
    fun `an image skips video validation entirely`() {
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0)
        val asset = stage(png, "image/png", "resim.png")

        val completed = service.completeUpload(ownerId, asset.id)

        assertEquals("READY", completed.processingStatus)
        assertNull(completed.durationSeconds)
        assertNull(completed.width)
    }

    // ------------------------------------------------------------- kapak gorseli

    @Test
    fun `a thumbnail can be attached to a video`() {
        val thumbnailId = stageReadyImage()
        val asset = stage(Mp4Fixtures.mp4(), "video/mp4", "demo.mp4")

        val completed = service.completeUpload(
            ownerId,
            asset.id,
            CompleteMediaUploadRequest(thumbnailMediaId = thumbnailId.toString()),
        )

        assertNotNull(completed.thumbnailUrl)
        assertEquals(thumbnailId, repository.findById(asset.id)?.thumbnailMediaId)
    }

    @Test
    fun `a thumbnail cannot be attached to an image`() {
        val thumbnailId = stageReadyImage()
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0)
        val asset = stage(png, "image/png", "resim.png")

        assertEquals("THUMBNAIL_NOT_ALLOWED", assertFailsWith {
            service.completeUpload(ownerId, asset.id, CompleteMediaUploadRequest(thumbnailId.toString()))
        }.code)
    }

    @Test
    fun `a video cannot be its own thumbnail`() {
        val otherVideo = stage(Mp4Fixtures.mp4(), "video/mp4", "baska.mp4")
        service.completeUpload(ownerId, otherVideo.id)
        val asset = stage(Mp4Fixtures.mp4(), "video/mp4", "demo.mp4")

        assertEquals("THUMBNAIL_MUST_BE_IMAGE", assertFailsWith {
            service.completeUpload(ownerId, asset.id, CompleteMediaUploadRequest(otherVideo.id.toString()))
        }.code)
    }

    @Test
    fun `someone else's image cannot be used as a thumbnail`() {
        val strangerPng = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0)
        val strangerId = UUID.randomUUID()
        val strangerUpload = service.createUpload(strangerId, CreateMediaUploadRequest("x.png", "image/png", strangerPng.size.toLong()))
        val asset = stage(Mp4Fixtures.mp4(), "video/mp4", "demo.mp4")

        assertEquals("MEDIA_NOT_AVAILABLE", assertFailsWith {
            service.completeUpload(ownerId, asset.id, CompleteMediaUploadRequest(strangerUpload.mediaId))
        }.code)
    }

    // ------------------------------------------------------------------ durum ucu

    @Test
    fun `the status endpoint reports playability`() {
        val asset = stage(Mp4Fixtures.mp4(durationSeconds = 5.0, width = 640, height = 480), "video/mp4", "demo.mp4")

        val pending = service.status(ownerId, asset.id)
        assertFalse(pending.playable, "tamamlanmadan oynatilabilir sayilmamali")

        service.completeUpload(ownerId, asset.id)
        val ready = service.status(ownerId, asset.id)

        assertTrue(ready.playable)
        assertEquals("READY", ready.processingStatus)
        assertEquals(640, ready.width)
        assertEquals(5.0, assertNotNull(ready.durationSeconds), 0.001)
    }

    @Test
    fun `a failed video reports why`() {
        val asset = stage(Mp4Fixtures.mp4(durationSeconds = 999.0), "video/mp4", "uzun.mp4")
        runCatching { service.completeUpload(ownerId, asset.id) }

        val status = service.status(ownerId, asset.id)

        assertFalse(status.playable)
        assertEquals("FAILED", status.processingStatus)
        assertNotNull(status.failureReason)
    }

    @Test
    fun `another user cannot read the status`() {
        val asset = stage(Mp4Fixtures.mp4(), "video/mp4", "demo.mp4")

        assertEquals("MEDIA_NOT_FOUND", assertFailsWith { service.status(UUID.randomUUID(), asset.id) }.code)
    }

    private inline fun assertFailsWith(block: () -> Unit): ApiException =
        kotlin.test.assertFailsWith<ApiException> { block() }
}
