package com.nexi.notifications

import com.nexi.auth.ApiException
import com.nexi.posts.FakeObjectStorage
import io.ktor.http.HttpStatusCode
import java.time.Clock
import java.time.Duration
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

class NotificationServiceTest {
    private val now = Instant.parse("2026-08-23T00:00:00Z")
    private val repository = InMemoryNotificationRepository(now)
    private val service = NotificationService(repository, FakeObjectStorage(), Clock.fixed(now, ZoneOffset.UTC))

    private val meId = repository.addUser("Gizem Derici", "gizem")
    private val actorId = repository.addUser("Mert Arslan", "mert")
    private val postId = UUID.randomUUID()

    private fun like(actor: UUID = actorId, target: UUID = postId) =
        service.emit(meId, actor, NotificationType.POST_LIKE, NotificationTargetType.POST, target)

    // -------------------------------------------------------------- uretim

    @Test
    fun `a notification is produced and listed`() {
        like()

        val page = service.page(meId, null, null)

        assertEquals(1, page.items.size)
        assertEquals("POST_LIKE", page.items.single().type)
        assertEquals("mert", assertNotNull(page.items.single().actor).username)
        assertEquals(postId.toString(), page.items.single().targetId)
        assertEquals(1, page.unreadCount)
    }

    @Test
    fun `your own action never notifies you`() {
        service.emit(meId, meId, NotificationType.POST_LIKE, NotificationTargetType.POST, postId)

        assertTrue(service.page(meId, null, null).items.isEmpty())
    }

    @Test
    fun `the same event does not pile up`() {
        // Begen - kaldir - tekrar begen: tek satir kalmali.
        like()
        like()
        like()

        assertEquals(1, service.page(meId, null, null).items.size)
    }

    @Test
    fun `a repeated event brings the notification back to unread`() {
        like()
        service.markAllRead(meId)
        assertEquals(0, service.unreadCount(meId).unreadCount)

        like()

        assertEquals(1, service.unreadCount(meId).unreadCount)
    }

    @Test
    fun `different actors and different targets stay separate`() {
        val otherActor = repository.addUser("Ayse Yilmaz", "ayse")
        val otherPost = UUID.randomUUID()

        like()
        like(actor = otherActor)
        like(target = otherPost)

        assertEquals(3, service.page(meId, null, null).items.size)
    }

    @Test
    fun `a system notification has no actor`() {
        service.emit(meId, null, NotificationType.SYSTEM, null, null)

        val item = service.page(meId, null, null).items.single()
        assertEquals("SYSTEM", item.type)
        assertNull(item.actor)
        assertNull(item.targetId)
    }

    @Test
    fun `blocked actors disappear from the list and the counter`() {
        like()
        repository.blocks += meId to actorId

        assertTrue(service.page(meId, null, null).items.isEmpty())
        assertEquals(0, service.unreadCount(meId).unreadCount)
    }

    // -------------------------------------------------------------- okundu

    @Test
    fun `marking one read only clears that one`() {
        like()
        like(target = UUID.randomUUID())
        val first = service.page(meId, null, null).items.first()

        val result = service.markRead(meId, UUID.fromString(first.id))

        assertEquals(1, result.unreadCount)
        assertTrue(service.page(meId, null, null).items.single { it.id == first.id }.read)
    }

    @Test
    fun `marking an already read notification is not an error`() {
        like()
        val id = UUID.fromString(service.page(meId, null, null).items.single().id)
        service.markRead(meId, id)

        assertEquals(0, service.markRead(meId, id).unreadCount)
    }

    @Test
    fun `read-all clears everything`() {
        like()
        like(target = UUID.randomUUID())

        assertEquals(0, service.markAllRead(meId).unreadCount)
        assertTrue(service.page(meId, null, null).items.all { it.read })
    }

    @Test
    fun `you cannot touch someone else's notification`() {
        like()
        val id = UUID.fromString(service.page(meId, null, null).items.single().id)

        // Baskasi okundu isaretleyemez; sayaci degismez.
        assertEquals(0, service.markRead(actorId, id).unreadCount)
        assertEquals(1, service.unreadCount(meId).unreadCount)

        assertEquals(HttpStatusCode.NotFound, assertFailsWith<ApiException> {
            service.delete(actorId, id)
        }.status)
    }

    // --------------------------------------------------------------- silme

    @Test
    fun `deleting removes it from the list`() {
        like()
        val id = UUID.fromString(service.page(meId, null, null).items.single().id)

        service.delete(meId, id)

        assertTrue(service.page(meId, null, null).items.isEmpty())
        assertFailsWith<ApiException> { service.delete(meId, id) }
    }

    // ------------------------------------------------------------ saklama

    @Test
    fun `old notifications are swept and recent ones are kept`() {
        like()
        like(target = UUID.randomUUID())

        // Ilk bildirim baseTime'da, ikincisi bir saniye sonra.
        assertEquals(1, repository.deleteOlderThan(now.plusSeconds(1), 100))
        assertEquals(1, service.page(meId, null, null).items.size)
    }

    @Test
    fun `nothing is swept when everything is recent`() {
        like()

        assertEquals(0, repository.deleteOlderThan(now.minus(Duration.ofDays(30)), 100))
    }

    // ----------------------------------------------------------- sayfalama

    @Test
    fun `notifications page newest first without repeats`() {
        repeat(5) { like(target = UUID.randomUUID()) }

        val first = service.page(meId, null, 2)
        assertEquals(2, first.items.size)
        val second = service.page(meId, assertNotNull(first.nextCursor), 2)
        val third = service.page(meId, assertNotNull(second.nextCursor), 2)

        val ids = (first.items + second.items + third.items).map { it.id }
        assertEquals(5, ids.size)
        assertEquals(ids.distinct().size, ids.size)
        assertNull(third.nextCursor)
    }

    @Test
    fun `a broken cursor is refused`() {
        assertEquals("INVALID_CURSOR", assertFailsWith<ApiException> {
            service.page(meId, "bozuk-imlec", null)
        }.code)
    }

    @Test
    fun `unread count matches the list`() {
        like()
        like(target = UUID.randomUUID())

        assertEquals(2, service.unreadCount(meId).unreadCount)
        assertEquals(2, service.page(meId, null, null).unreadCount)
        assertFalse(service.page(meId, null, null).items.any { it.read })
    }
}
