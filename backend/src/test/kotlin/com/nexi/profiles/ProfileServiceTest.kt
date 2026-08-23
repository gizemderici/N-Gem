package com.nexi.profiles

import com.nexi.auth.ApiException
import com.nexi.posts.FakeObjectStorage
import io.ktor.http.HttpStatusCode
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProfileServiceTest {
    private val now = Instant.parse("2026-08-23T00:00:00Z")
    private val repository = InMemoryProfileRepository(now)
    private val service = ProfileService(repository, FakeObjectStorage(), Clock.fixed(now, ZoneOffset.UTC))

    private val viewerId = repository.addUser("Gizem Derici", "gizem")
    private val mertId = repository.addUser("Mert Arslan", "mert")
    private val ayseId = repository.addUser("Ayşe Yılmaz", "ayse")

    @Test
    fun `profile carries real counts and the viewer's relationship`() {
        repository.postCounts[mertId] = 7
        service.setFollow(viewerId, "mert", true)
        service.setFollow(ayseId, "mert", true)

        val profile = service.profile(viewerId, "mert")

        assertEquals("Mert Arslan", profile.fullName)
        assertEquals(7, profile.postCount)
        assertEquals(2, profile.followerCount)
        assertEquals(0, profile.followingCount)
        assertTrue(profile.followedByMe)
        assertFalse(profile.isMe)
    }

    @Test
    fun `your own profile is marked and never followable`() {
        assertTrue(service.profile(viewerId, "gizem").isMe)

        val error = assertFailsWith<ApiException> { service.setFollow(viewerId, "gizem", true) }
        assertEquals("CANNOT_FOLLOW_SELF", error.code)
    }

    @Test
    fun `following is idempotent in both directions`() {
        assertEquals(1, service.setFollow(viewerId, "mert", true).followerCount)
        assertEquals(1, service.setFollow(viewerId, "mert", true).followerCount)

        assertEquals(0, service.setFollow(viewerId, "mert", false).followerCount)
        assertEquals(0, service.setFollow(viewerId, "mert", false).followerCount)

        assertFalse(service.profile(viewerId, "mert").followedByMe)
    }

    @Test
    fun `unknown users are reported as not found`() {
        assertEquals(HttpStatusCode.NotFound, assertFailsWith<ApiException> {
            service.profile(viewerId, "yokboyle")
        }.status)

        assertEquals("USER_NOT_FOUND", assertFailsWith<ApiException> {
            service.setFollow(viewerId, "yokboyle", true)
        }.code)
    }

    @Test
    fun `follower and following lists see it from both sides`() {
        service.setFollow(viewerId, "mert", true)
        service.setFollow(ayseId, "mert", true)

        val followers = service.followers(viewerId, "mert", null, null)
        assertEquals(2, followers.totalCount)
        assertEquals(setOf("gizem", "ayse"), followers.items.map { it.username }.toSet())
        // Kendini listede görürsen `isMe` işaretli gelir.
        assertTrue(followers.items.single { it.username == "gizem" }.isMe)

        val following = service.following(viewerId, "gizem", null, null)
        assertEquals(1, following.totalCount)
        assertEquals("mert", following.items.single().username)
        assertTrue(following.items.single().followedByMe)
    }

    @Test
    fun `follower list pages with a cursor`() {
        val extras = (1..4).map { repository.addUser("Kullanıcı $it", "kullanici$it") }
        extras.forEach { repository.setFollow(it, mertId, true, now) }

        val first = service.followers(viewerId, "mert", null, 2)
        assertEquals(2, first.items.size)
        assertEquals(4, first.totalCount)
        val cursor = assertNotNull(first.nextCursor)

        val second = service.followers(viewerId, "mert", cursor, 2)
        assertEquals(2, second.items.size)
        assertNull(second.nextCursor)

        val usernames = (first.items + second.items).map { it.username }
        assertEquals(usernames.distinct().size, usernames.size)
    }

    @Test
    fun `a broken cursor is rejected`() {
        assertEquals("INVALID_CURSOR", assertFailsWith<ApiException> {
            service.followers(viewerId, "mert", "bozuk-imlec", null)
        }.code)
    }
}
