package com.nexi.comments

import com.nexi.auth.ApiException
import io.ktor.http.HttpStatusCode
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CommentServiceTest {
    private val now = Instant.parse("2026-08-23T00:00:00Z")
    private val repository = InMemoryCommentRepository(now)
    private val service = CommentService(repository, Clock.fixed(now, ZoneOffset.UTC))

    private val postOwnerId = UUID.randomUUID()
    private val commenterId = UUID.randomUUID()
    private val strangerId = UUID.randomUUID()
    private val postId = repository.addPost(postOwnerId)

    @Test
    fun `a comment is created and listed oldest first`() {
        service.create(commenterId, postId, CreateCommentRequest("İlk yorum"))
        service.create(postOwnerId, postId, CreateCommentRequest("İkinci yorum"))

        val page = service.page(commenterId, postId, null, null)

        assertEquals(listOf("İlk yorum", "İkinci yorum"), page.items.map { it.text })
        assertEquals(2, page.totalCount)
        assertNull(page.nextCursor)
    }

    @Test
    fun `empty and overlong comments are rejected`() {
        assertEquals("EMPTY_COMMENT", assertFailsWith<ApiException> {
            service.create(commenterId, postId, CreateCommentRequest("   "))
        }.code)

        assertEquals("COMMENT_TOO_LONG", assertFailsWith<ApiException> {
            service.create(commenterId, postId, CreateCommentRequest("a".repeat(CommentService.MAX_LENGTH + 1)))
        }.code)

        assertEquals(0, service.page(commenterId, postId, null, null).totalCount)
    }

    @Test
    fun `commenting on a missing post fails`() {
        val error = assertFailsWith<ApiException> {
            service.create(commenterId, UUID.randomUUID(), CreateCommentRequest("Hayalet gönderi"))
        }
        assertEquals(HttpStatusCode.NotFound, error.status)
        assertEquals("POST_NOT_FOUND", error.code)
    }

    @Test
    fun `both the comment author and the post owner may delete`() {
        val byCommenter = service.create(commenterId, postId, CreateCommentRequest("Yazarın yorumu"))
        val byStranger = service.create(strangerId, postId, CreateCommentRequest("Başkasının yorumu"))

        // Yorumun sahibi kendi yorumunu silebilir.
        service.delete(commenterId, UUID.fromString(byCommenter.id))
        // Gönderinin sahibi de altındaki yabancı yorumu silebilir.
        service.delete(postOwnerId, UUID.fromString(byStranger.id))

        assertEquals(0, service.page(postOwnerId, postId, null, null).totalCount)
    }

    @Test
    fun `an unrelated user cannot delete and is not told the comment exists`() {
        val comment = service.create(commenterId, postId, CreateCommentRequest("Dokunma"))

        val error = assertFailsWith<ApiException> {
            service.delete(strangerId, UUID.fromString(comment.id))
        }
        // Yetkisiz kullanıcıya 403 dönmek yorumun varlığını açık ederdi.
        assertEquals(HttpStatusCode.NotFound, error.status)
        assertEquals("COMMENT_NOT_FOUND", error.code)
        assertEquals(1, service.page(commenterId, postId, null, null).totalCount)
    }

    @Test
    fun `deleting the same comment twice fails the second time`() {
        val comment = service.create(commenterId, postId, CreateCommentRequest("Tek sefer"))
        service.delete(commenterId, UUID.fromString(comment.id))

        assertFailsWith<ApiException> { service.delete(commenterId, UUID.fromString(comment.id)) }
    }

    @Test
    fun `deletableByMe reflects who is looking`() {
        service.create(commenterId, postId, CreateCommentRequest("Yorum"))

        assertTrue(service.page(commenterId, postId, null, null).items.single().deletableByMe)
        assertTrue(service.page(postOwnerId, postId, null, null).items.single().deletableByMe)
        assertFalse(service.page(strangerId, postId, null, null).items.single().deletableByMe)
    }

    @Test
    fun `pages follow the cursor without repeating or skipping`() {
        repeat(5) { service.create(commenterId, postId, CreateCommentRequest("Yorum $it")) }

        val first = service.page(commenterId, postId, null, 2)
        assertEquals(2, first.items.size)
        val cursor = assertNotNull(first.nextCursor)

        val second = service.page(commenterId, postId, cursor, 2)
        val third = service.page(commenterId, postId, assertNotNull(second.nextCursor), 2)

        val texts = (first.items + second.items + third.items).map { it.text }
        assertEquals(listOf("Yorum 0", "Yorum 1", "Yorum 2", "Yorum 3", "Yorum 4"), texts)
        assertNull(third.nextCursor)
        // Sayaç sayfa boyutundan değil, gönderinin tamamından geliyor.
        assertEquals(5, first.totalCount)
    }

    @Test
    fun `a broken cursor is rejected`() {
        assertEquals("INVALID_CURSOR", assertFailsWith<ApiException> {
            service.page(commenterId, postId, "bozuk-imlec", null)
        }.code)
    }
}
