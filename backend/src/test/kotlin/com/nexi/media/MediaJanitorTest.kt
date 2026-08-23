package com.nexi.media

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MediaJanitorTest {
    private val now = Instant.parse("2026-08-23T12:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val repository = InMemoryMediaRepository()
    private val storage = FakeObjectStorage()
    private val janitor = MediaJanitor(repository, storage, clock)

    private fun asset(status: MediaStatus, age: Duration): MediaAsset {
        val id = UUID.randomUUID()
        val timestamp = now.minus(age)
        return MediaAsset(
            id = id,
            ownerId = UUID.randomUUID(),
            storageKey = "users/x/media/$id.png",
            originalFilename = "x.png",
            mimeType = "image/png",
            declaredSizeBytes = 10,
            actualSizeBytes = null,
            status = status,
            createdAt = timestamp,
            updatedAt = timestamp,
        ).also { repository.create(it) }
    }

    @Test
    fun `abandoned pending uploads are removed from storage and marked deleted`() {
        val abandoned = asset(MediaStatus.PENDING, Duration.ofHours(48))
        storage.objects[abandoned.storageKey] = StoredObjectInfo(10, "image/png", ByteArray(0))

        val cleaned = janitor.sweepAbandonedUploads(Duration.ofHours(24))

        assertEquals(1, cleaned)
        assertTrue(abandoned.storageKey in storage.deletedKeys)
        assertEquals(MediaStatus.DELETED, repository.findById(abandoned.id)?.status)
    }

    @Test
    fun `recent uploads and other statuses are left alone`() {
        val recent = asset(MediaStatus.PENDING, Duration.ofMinutes(30))
        val ready = asset(MediaStatus.READY, Duration.ofDays(30))

        assertEquals(0, janitor.sweepAbandonedUploads(Duration.ofHours(24)))

        assertEquals(MediaStatus.PENDING, repository.findById(recent.id)?.status)
        assertEquals(MediaStatus.READY, repository.findById(ready.id)?.status)
        assertTrue(storage.deletedKeys.isEmpty())
    }

    @Test
    fun `a missing storage object does not stop the sweep`() {
        // Kullanıcı adresi aldı ama dosyayı hiç göndermedi: depoda nesne yok.
        val neverUploaded = asset(MediaStatus.PENDING, Duration.ofHours(48))

        val cleaned = janitor.sweepAbandonedUploads(Duration.ofHours(24))

        assertEquals(1, cleaned)
        assertEquals(MediaStatus.DELETED, repository.findById(neverUploaded.id)?.status)
    }
}
