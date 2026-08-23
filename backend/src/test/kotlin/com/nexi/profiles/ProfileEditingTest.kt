package com.nexi.profiles

import com.nexi.auth.ApiException
import com.nexi.posts.FakeObjectStorage
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProfileEditingTest {
    private val now = Instant.parse("2026-08-23T00:00:00Z")
    private val repository = InMemoryProfileRepository(now)
    private val storage = FakeObjectStorage()
    private val service = ProfileService(repository, storage, Clock.fixed(now, ZoneOffset.UTC))

    private val meId = repository.addUser("Gizem Derici", "gizem")
    private val otherId = repository.addUser("Mert Arslan", "mert")

    // ------------------------------------------------------------------ bio

    @Test
    fun `bio is written and read back`() {
        val updated = service.updateProfile(meId, "gizem", UpdateProfileRequest(bio = "  Ürün ve backend.  "))

        assertEquals("Ürün ve backend.", updated.bio)
        assertEquals("Ürün ve backend.", service.profile(otherId, "gizem").bio)
    }

    @Test
    fun `an empty bio clears it, an absent bio leaves it alone`() {
        service.updateProfile(meId, "gizem", UpdateProfileRequest(bio = "İlk hâli"))

        // Yalnizca ad gonderilince biyografi duruyor.
        val nameOnly = service.updateProfile(meId, "gizem", UpdateProfileRequest(fullName = "Gizem D."))
        assertEquals("İlk hâli", nameOnly.bio)
        assertEquals("Gizem D.", nameOnly.fullName)

        // Bos metin gondermek siliyor.
        assertNull(service.updateProfile(meId, "gizem", UpdateProfileRequest(bio = "   ")).bio)
    }

    @Test
    fun `an overlong bio is rejected and nothing is written`() {
        val error = assertFailsWith<ApiException> {
            service.updateProfile(meId, "gizem", UpdateProfileRequest(bio = "a".repeat(ProfileService.MAX_BIO_LENGTH + 1)))
        }
        assertEquals("BIO_TOO_LONG", error.code)
        assertNull(service.profile(meId, "gizem").bio)
    }

    @Test
    fun `full name validation matches registration`() {
        assertEquals("INVALID_FULL_NAME", assertFailsWith<ApiException> {
            service.updateProfile(meId, "gizem", UpdateProfileRequest(fullName = "ab"))
        }.code)

        // Aradaki fazla bosluklar tek bosluga iniyor.
        assertEquals("Gizem   Derici".replace(Regex("\\s+"), " "),
            service.updateProfile(meId, "gizem", UpdateProfileRequest(fullName = "Gizem   Derici")).fullName)
    }

    // --------------------------------------------------------------- avatar

    @Test
    fun `an owned ready image becomes the avatar and yields a timed url`() {
        val mediaId = repository.addMedia(meId)

        val updated = service.setAvatar(meId, "gizem", SetAvatarRequest(mediaId.toString()))

        val url = assertNotNull(updated.avatarUrl)
        assertTrue(url.contains(mediaId.toString()))
        assertEquals(com.nexi.media.AvatarUrls.EXPIRY.seconds, updated.avatarUrlExpiresInSeconds)
    }

    @Test
    fun `the avatar is visible to other viewers too`() {
        val mediaId = repository.addMedia(meId)
        service.setAvatar(meId, "gizem", SetAvatarRequest(mediaId.toString()))

        assertNotNull(service.profile(otherId, "gizem").avatarUrl)
    }

    @Test
    fun `someone else's media cannot be used and its existence is not revealed`() {
        val theirs = repository.addMedia(otherId)

        val error = assertFailsWith<ApiException> {
            service.setAvatar(meId, "gizem", SetAvatarRequest(theirs.toString()))
        }
        // Var olmayan medyayla ayni hata: baskasinin gorselinin varligi aciga cikmasin.
        assertEquals("MEDIA_NOT_AVAILABLE", error.code)
        assertEquals(error.code, assertFailsWith<ApiException> {
            service.setAvatar(meId, "gizem", SetAvatarRequest(UUID.randomUUID().toString()))
        }.code)
    }

    @Test
    fun `a pending upload cannot be an avatar`() {
        val pending = repository.addMedia(meId, status = "PENDING")

        assertEquals("MEDIA_NOT_READY", assertFailsWith<ApiException> {
            service.setAvatar(meId, "gizem", SetAvatarRequest(pending.toString()))
        }.code)
    }

    @Test
    fun `a video cannot be an avatar`() {
        val video = repository.addMedia(meId, mimeType = "video/mp4")

        assertEquals("AVATAR_MUST_BE_IMAGE", assertFailsWith<ApiException> {
            service.setAvatar(meId, "gizem", SetAvatarRequest(video.toString()))
        }.code)
    }

    @Test
    fun `a malformed media id is rejected`() {
        assertEquals("INVALID_MEDIA_ID", assertFailsWith<ApiException> {
            service.setAvatar(meId, "gizem", SetAvatarRequest("uuid-degil"))
        }.code)
    }

    @Test
    fun `removing the avatar clears the url`() {
        val mediaId = repository.addMedia(meId)
        service.setAvatar(meId, "gizem", SetAvatarRequest(mediaId.toString()))

        val cleared = service.removeAvatar(meId, "gizem")

        assertNull(cleared.avatarUrl)
        assertNull(cleared.avatarUrlExpiresInSeconds)
    }

    @Test
    fun `avatars show up in follower lists`() {
        val mediaId = repository.addMedia(meId)
        service.setAvatar(meId, "gizem", SetAvatarRequest(mediaId.toString()))
        service.setFollow(meId, "mert", true)

        val followers = service.followers(otherId, "mert", null, null)

        assertNotNull(followers.items.single { it.username == "gizem" }.avatarUrl)
    }

    @Test
    fun `a user without an avatar reports null instead of a broken url`() {
        val profile = service.profile(meId, "mert")

        assertNull(profile.avatarUrl)
        assertNull(profile.avatarUrlExpiresInSeconds)
    }
}
