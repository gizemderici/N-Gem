package com.nexi.media

import com.nexi.auth.ApiException
import com.nexi.config.StorageConfig
import io.ktor.http.HttpStatusCode
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class MediaServiceTest {
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
    )
    private val service = MediaService(repository, storage, config, clock)
    private val ownerId = UUID.randomUUID()

    @Test
    fun `valid png upload becomes ready and receives download url`() {
        val signature = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
            0, 0, 0, 0,
        )
        val upload = service.createUpload(ownerId, CreateMediaUploadRequest("nexi.png", "image/png", signature.size.toLong()))
        val asset = assertNotNull(repository.findById(UUID.fromString(upload.mediaId)))
        storage.objects[asset.storageKey] = StoredObjectInfo(signature.size.toLong(), "image/png", signature)

        val completed = service.completeUpload(ownerId, asset.id)

        assertEquals("READY", completed.status)
        assertTrue(completed.downloadUrl!!.startsWith("http://public-storage/"))
        assertEquals(MediaStatus.READY, repository.findById(asset.id)?.status)
    }

    @Test
    fun `fake image is rejected and removed from storage`() {
        val bytes = "not-a-real-png".toByteArray()
        val upload = service.createUpload(ownerId, CreateMediaUploadRequest("fake.png", "image/png", bytes.size.toLong()))
        val asset = assertNotNull(repository.findById(UUID.fromString(upload.mediaId)))
        storage.objects[asset.storageKey] = StoredObjectInfo(bytes.size.toLong(), "image/png", bytes)

        val error = assertFailsWith<ApiException> { service.completeUpload(ownerId, asset.id) }

        assertEquals("INVALID_UPLOADED_FILE", error.code)
        assertEquals(MediaStatus.REJECTED, repository.findById(asset.id)?.status)
        assertTrue(asset.storageKey in storage.deletedKeys)
    }

    @Test
    fun `valid mp4 upload becomes ready`() {
        val signature = byteArrayOf(
            0, 0, 0, 24, 0x66, 0x74, 0x79, 0x70, 0x69, 0x73, 0x6F, 0x6D,
        )
        val upload = service.createUpload(ownerId, CreateMediaUploadRequest("demo.mp4", "video/mp4", signature.size.toLong()))
        val asset = assertNotNull(repository.findById(UUID.fromString(upload.mediaId)))
        storage.objects[asset.storageKey] = StoredObjectInfo(signature.size.toLong(), "video/mp4", signature)

        val completed = service.completeUpload(ownerId, asset.id)

        assertEquals("READY", completed.status)
        assertEquals("video/mp4", completed.mimeType)
    }

    @Test
    fun `another user cannot inspect an upload`() {
        val upload = service.createUpload(ownerId, CreateMediaUploadRequest("photo.jpg", "image/jpeg", 3))
        val error = assertFailsWith<ApiException> {
            service.get(UUID.randomUUID(), UUID.fromString(upload.mediaId))
        }
        assertEquals(HttpStatusCode.NotFound, error.status)
    }

    @Test
    fun `unsupported types and oversized files are rejected before storage`() {
        assertFailsWith<ApiException> {
            service.createUpload(ownerId, CreateMediaUploadRequest("archive.zip", "application/zip", 20))
        }
        assertFailsWith<ApiException> {
            service.createUpload(
                ownerId,
                CreateMediaUploadRequest("huge.jpg", "image/jpeg", config.maxImageSizeBytes + 1),
            )
        }
        assertTrue(repository.assets.isEmpty())
    }
}

private class InMemoryMediaRepository : MediaRepository {
    val assets = ConcurrentHashMap<UUID, MediaAsset>()
    override fun create(asset: MediaAsset) { assets[asset.id] = asset }
    override fun findById(id: UUID) = assets[id]
    override fun markReady(id: UUID, actualSizeBytes: Long, updatedAt: Instant): Boolean {
        var changed = false
        assets.computeIfPresent(id) { _, asset ->
            if (asset.status == MediaStatus.PENDING) {
                changed = true
                asset.copy(actualSizeBytes = actualSizeBytes, status = MediaStatus.READY, updatedAt = updatedAt)
            } else asset
        }
        return changed
    }
    override fun markStatus(id: UUID, status: MediaStatus, updatedAt: Instant): Boolean {
        var changed = false
        assets.computeIfPresent(id) { _, asset ->
            changed = true
            asset.copy(status = status, updatedAt = updatedAt)
        }
        return changed
    }
}

private class FakeObjectStorage : ObjectStorage {
    val objects = ConcurrentHashMap<String, StoredObjectInfo>()
    val deletedKeys = mutableSetOf<String>()
    override fun createUploadUrl(key: String, mimeType: String, expiresIn: Duration) = "http://public-storage/$key?upload"
    override fun inspect(key: String) = objects[key] ?: error("Object does not exist")
    override fun createDownloadUrl(key: String, expiresIn: Duration) = "http://public-storage/$key?download"
    override fun delete(key: String) {
        objects.remove(key)
        deletedKeys += key
    }
}
