package com.nexi.messaging

import com.nexi.auth.ApiException
import com.nexi.media.MediaStatus
import com.nexi.posts.FakeObjectStorage
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

class MessagingServiceTest {
    private val now = Instant.parse("2026-08-23T00:00:00Z")
    private val repository = InMemoryMessagingRepository(now)
    private val service = MessagingService(
        repository = repository,
        users = { username, viewerId ->
            repository.idByUsername(username)?.takeIf { other ->
                // Gercek arama profil sorgusundan geciyor; engelli kullanici donmuyor.
                repository.counterpartVisible(viewerId, other)
            }
        },
        storage = FakeObjectStorage(),
        clock = Clock.fixed(now, ZoneOffset.UTC),
    )

    private val meId = repository.addUser("Gizem Derici", "gizem")
    private val otherId = repository.addUser("Mert Arslan", "mert")
    private val strangerId = repository.addUser("Ayse Yilmaz", "ayse")

    private fun openWithMert(): UUID =
        UUID.fromString(service.openConversation(meId, CreateConversationRequest("mert")).id)

    // ------------------------------------------------------------- konusma

    @Test
    fun `opening the same conversation twice returns the same one`() {
        val first = openWithMert()
        val second = UUID.fromString(service.openConversation(meId, CreateConversationRequest("mert")).id)
        // Karsi taraf actiginda da ayni konusma gelmeli.
        val fromOther = UUID.fromString(service.openConversation(otherId, CreateConversationRequest("gizem")).id)

        assertEquals(first, second)
        assertEquals(first, fromOther)
    }

    @Test
    fun `you cannot message yourself`() {
        assertEquals("CANNOT_MESSAGE_SELF", assertFailsWith<ApiException> {
            service.openConversation(meId, CreateConversationRequest("gizem"))
        }.code)
    }

    @Test
    fun `a blocked user cannot be messaged`() {
        repository.blocks += meId to otherId

        assertEquals(HttpStatusCode.NotFound, assertFailsWith<ApiException> {
            service.openConversation(meId, CreateConversationRequest("mert"))
        }.status)
    }

    @Test
    fun `conversations without messages do not show in the list`() {
        openWithMert()

        assertTrue(service.conversations(meId, null, null).items.isEmpty())
    }

    @Test
    fun `a conversation appears once a message is sent`() {
        val id = openWithMert()
        service.send(meId, id, SendMessageRequest(text = "Merhaba"))

        val list = service.conversations(meId, null, null)

        assertEquals(1, list.items.size)
        assertEquals("mert", list.items.single().other.username)
    }

    // --------------------------------------------------------------- mesaj

    @Test
    fun `messages come back newest first`() {
        val id = openWithMert()
        service.send(meId, id, SendMessageRequest(text = "Birinci"))
        service.send(otherId, id, SendMessageRequest(text = "Ikinci"))
        service.send(meId, id, SendMessageRequest(text = "Ucuncu"))

        val page = service.messages(meId, id, null, null)

        assertEquals(listOf("Ucuncu", "Ikinci", "Birinci"), page.items.map { it.text })
        assertTrue(page.items.first().mineByMe)
        assertFalse(page.items[1].mineByMe)
    }

    @Test
    fun `an empty message is refused`() {
        val id = openWithMert()

        assertEquals("EMPTY_MESSAGE", assertFailsWith<ApiException> {
            service.send(meId, id, SendMessageRequest(text = "   "))
        }.code)
    }

    @Test
    fun `an overlong message is refused`() {
        val id = openWithMert()

        assertEquals("MESSAGE_TOO_LONG", assertFailsWith<ApiException> {
            service.send(meId, id, SendMessageRequest(text = "a".repeat(MessagingService.MAX_TEXT_LENGTH + 1)))
        }.code)
    }

    @Test
    fun `media type decides the message type`() {
        val id = openWithMert()
        val image = repository.addMedia(meId, "image/png")
        val video = repository.addMedia(meId, "video/mp4")

        assertEquals("IMAGE", service.send(meId, id, SendMessageRequest(mediaId = image.toString())).type)
        assertEquals("VIDEO", service.send(meId, id, SendMessageRequest(mediaId = video.toString())).type)
        assertEquals("TEXT", service.send(meId, id, SendMessageRequest(text = "duz metin")).type)
    }

    @Test
    fun `someone else's media cannot be sent`() {
        val id = openWithMert()
        val theirs = repository.addMedia(strangerId)

        assertEquals("MEDIA_NOT_AVAILABLE", assertFailsWith<ApiException> {
            service.send(meId, id, SendMessageRequest(mediaId = theirs.toString()))
        }.code)
    }

    @Test
    fun `unfinished media cannot be sent`() {
        val id = openWithMert()
        val pending = repository.addMedia(meId, status = MediaStatus.PENDING)

        assertEquals("MEDIA_NOT_READY", assertFailsWith<ApiException> {
            service.send(meId, id, SendMessageRequest(mediaId = pending.toString()))
        }.code)
    }

    @Test
    fun `outsiders cannot read or write and are not told the conversation exists`() {
        val id = openWithMert()
        service.send(meId, id, SendMessageRequest(text = "Ozel"))

        listOf<() -> Unit>(
            { service.messages(strangerId, id, null, null) },
            { service.send(strangerId, id, SendMessageRequest(text = "Araya girdim")) },
            { service.markRead(strangerId, id) },
        ).forEach { action ->
            val error = assertFailsWith<ApiException> { action() }
            assertEquals(HttpStatusCode.NotFound, error.status)
            assertEquals("CONVERSATION_NOT_FOUND", error.code)
        }
    }

    @Test
    fun `blocking hides an existing conversation from both sides`() {
        val id = openWithMert()
        service.send(meId, id, SendMessageRequest(text = "Merhaba"))
        repository.blocks += meId to otherId

        assertTrue(service.conversations(meId, null, null).items.isEmpty())
        assertTrue(service.conversations(otherId, null, null).items.isEmpty())
        assertEquals("CONVERSATION_NOT_FOUND", assertFailsWith<ApiException> {
            service.send(meId, id, SendMessageRequest(text = "Yine ben"))
        }.code)
    }

    // -------------------------------------------------------------- okundu

    @Test
    fun `unread counts only the other side's messages`() {
        val id = openWithMert()
        service.send(otherId, id, SendMessageRequest(text = "Bir"))
        service.send(otherId, id, SendMessageRequest(text = "Iki"))
        service.send(meId, id, SendMessageRequest(text = "Benim mesajim"))

        assertEquals(2, service.conversations(meId, null, null).items.single().unreadCount)
        // Kendi mesajim karsi taraf icin okunmamis sayilir.
        assertEquals(1, service.conversations(otherId, null, null).items.single().unreadCount)
    }

    @Test
    fun `marking read clears the counter`() {
        val id = openWithMert()
        service.send(otherId, id, SendMessageRequest(text = "Bir"))

        val receipt = service.markRead(meId, id)

        assertEquals(0, receipt.unreadCount)
        assertEquals(0, service.conversations(meId, null, null).totalUnread)
    }

    @Test
    fun `a message sent after reading counts again`() {
        val id = openWithMert()
        service.send(otherId, id, SendMessageRequest(text = "Bir"))
        service.markRead(meId, id)

        service.send(otherId, id, SendMessageRequest(text = "Iki"))

        assertEquals(1, service.conversations(meId, null, null).items.single().unreadCount)
    }

    @Test
    fun `seenByOther is only reported for your own messages`() {
        val id = openWithMert()
        service.send(meId, id, SendMessageRequest(text = "Gordun mu"))

        assertFalse(service.messages(meId, id, null, null).items.single().seenByOther)

        service.markRead(otherId, id)
        assertTrue(service.messages(meId, id, null, null).items.single().seenByOther)
        // Karsi taraf kendi listesinde bu bilgiyi gormemeli; mesaj onun degil.
        assertFalse(service.messages(otherId, id, null, null).items.single().seenByOther)
    }

    // --------------------------------------------------------------- silme

    @Test
    fun `only the sender can delete a message`() {
        val id = openWithMert()
        val message = service.send(meId, id, SendMessageRequest(text = "Silinecek"))
        val messageId = UUID.fromString(message.id)

        assertEquals(HttpStatusCode.NotFound, assertFailsWith<ApiException> {
            service.deleteMessage(otherId, messageId)
        }.status)

        service.deleteMessage(meId, messageId)
        assertTrue(service.messages(meId, id, null, null).items.isEmpty())
    }

    @Test
    fun `deleting twice fails the second time`() {
        val id = openWithMert()
        val messageId = UUID.fromString(service.send(meId, id, SendMessageRequest(text = "Tek sefer")).id)
        service.deleteMessage(meId, messageId)

        assertFailsWith<ApiException> { service.deleteMessage(meId, messageId) }
    }

    // ----------------------------------------------------------- sayfalama

    @Test
    fun `messages page without repeats`() {
        val id = openWithMert()
        repeat(5) { service.send(meId, id, SendMessageRequest(text = "Mesaj $it")) }

        val first = service.messages(meId, id, null, 2)
        assertEquals(2, first.items.size)
        val second = service.messages(meId, id, assertNotNull(first.nextCursor), 2)
        val third = service.messages(meId, id, assertNotNull(second.nextCursor), 2)

        val texts = (first.items + second.items + third.items).map { it.text }
        assertEquals(listOf("Mesaj 4", "Mesaj 3", "Mesaj 2", "Mesaj 1", "Mesaj 0"), texts)
        assertNull(third.nextCursor)
    }

    @Test
    fun `a broken cursor is refused`() {
        val id = openWithMert()

        assertEquals("INVALID_CURSOR", assertFailsWith<ApiException> {
            service.messages(meId, id, "bozuk-imlec", null)
        }.code)
    }
}

/** Testte kullanici aramasi da engeli hesaba katsin diye küçük bir yardımcı. */
internal fun InMemoryMessagingRepository.counterpartVisible(viewerId: UUID, otherId: UUID): Boolean =
    (viewerId to otherId) !in blocks && (otherId to viewerId) !in blocks
