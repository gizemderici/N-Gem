package com.nexi.messaging

import com.nexi.auth.ApiException
import com.nexi.auth.validation
import com.nexi.media.MediaAsset
import com.nexi.media.ObjectStorage
import com.nexi.notifications.NotificationSink
import com.nexi.notifications.NotificationTargetType
import com.nexi.notifications.NotificationType
import com.nexi.posts.withAvatar
import io.ktor.http.HttpStatusCode
import java.nio.charset.StandardCharsets
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.UUID

class MessagingService(
    private val repository: MessagingRepository,
    private val users: UserLookup,
    private val storage: ObjectStorage,
    private val clock: Clock = Clock.systemUTC(),
    private val notifications: NotificationSink = NotificationSink.NOOP,
) {
    /** Kullanıcı adını kimliğe çevirir; engelli kullanıcı için `null` döner. */
    fun interface UserLookup {
        fun visibleUserId(username: String, viewerId: UUID): UUID?
    }

    private val mediaUrlExpiry: Duration = Duration.ofMinutes(15)

    // ------------------------------------------------------------- konuşma

    /** Bire bir konuşmayı getirir, yoksa açar. Aynı kişiyle ikinci konuşma açılmaz. */
    fun openConversation(viewerId: UUID, request: CreateConversationRequest): ConversationResponse {
        // Engelli kullanıcı `visibleUserId` sorgusundan dönmüyor; konuşma
        // başlatmak "böyle bir kullanıcı yok" cevabı alıyor.
        val otherId = users.visibleUserId(request.username.trim(), viewerId)
            ?: throw ApiException(HttpStatusCode.NotFound, "USER_NOT_FOUND", "Kullanıcı bulunamadı.")
        if (otherId == viewerId) {
            throw validation("CANNOT_MESSAGE_SELF", "Kendine mesaj gönderemezsin.", "username")
        }

        val conversationId = repository.findOrCreateDirect(viewerId, otherId, clock.instant())
        val other = repository.counterpart(conversationId, viewerId)
            ?: throw ApiException(HttpStatusCode.NotFound, "USER_NOT_FOUND", "Kullanıcı bulunamadı.")

        return ConversationResponse(
            id = conversationId.toString(),
            other = other.withAvatar(storage),
            unreadCount = repository.unreadCount(conversationId, viewerId),
        )
    }

    /** Henüz mesaj gönderilmemiş konuşmalar listede görünmez. */
    fun conversations(viewerId: UUID, rawCursor: String?, requestedLimit: Int?): ConversationPageResponse {
        val limit = (requestedLimit ?: DEFAULT_PAGE_SIZE).coerceIn(1, MAX_PAGE_SIZE)
        val rows = repository.conversations(viewerId, rawCursor?.let { decodeCursor(it) }, limit + 1)
        val hasMore = rows.size > limit
        val items = rows.take(limit)

        return ConversationPageResponse(
            items = items.map {
                ConversationResponse(
                    id = it.id.toString(),
                    other = it.other.withAvatar(storage),
                    unreadCount = it.unreadCount,
                    lastMessageAt = it.lastMessageAt?.toString(),
                )
            },
            nextCursor = if (hasMore) {
                items.lastOrNull()?.lastMessageAt?.let { encodeCursor(it, items.last().id) }
            } else {
                null
            },
            totalUnread = repository.totalUnread(viewerId),
        )
    }

    // -------------------------------------------------------------- mesaj

    fun messages(viewerId: UUID, conversationId: UUID, rawCursor: String?, requestedLimit: Int?): MessagePageResponse {
        requireMembership(conversationId, viewerId)

        val limit = (requestedLimit ?: DEFAULT_PAGE_SIZE).coerceIn(1, MAX_PAGE_SIZE)
        val rows = repository.messages(conversationId, viewerId, rawCursor?.let { decodeCursor(it) }, limit + 1)
        val hasMore = rows.size > limit
        val items = rows.take(limit)

        return MessagePageResponse(
            items = items.map { it.toResponse(viewerId) },
            nextCursor = if (hasMore) {
                items.lastOrNull()?.let { encodeCursor(it.message.createdAt, it.message.id) }
            } else {
                null
            },
        )
    }

    fun send(viewerId: UUID, conversationId: UUID, request: SendMessageRequest): MessageResponse {
        requireMembership(conversationId, viewerId)

        val text = request.text?.trim()?.takeIf { it.isNotEmpty() }
        if (text != null && text.length > MAX_TEXT_LENGTH) {
            throw validation("MESSAGE_TOO_LONG", "Mesaj en fazla $MAX_TEXT_LENGTH karakter olabilir.", "text")
        }

        val mediaId = request.mediaId?.let { raw ->
            runCatching { UUID.fromString(raw) }.getOrElse {
                throw validation("INVALID_MEDIA_ID", "Medya kimliği geçersiz.", "mediaId")
            }
        }
        if (text == null && mediaId == null) {
            throw validation("EMPTY_MESSAGE", "Mesaj metni veya bir medya gerekli.", "text")
        }

        val messageType = mediaId?.let { resolveMediaType(it, viewerId) } ?: MessageType.TEXT
        val now = clock.instant()
        val details = repository.send(
            Message(
                id = UUID.randomUUID(),
                conversationId = conversationId,
                senderId = viewerId,
                type = messageType,
                body = text,
                mediaId = mediaId,
                status = MessageStatus.SENT,
                createdAt = now,
            )
        ) ?: throw ApiException(HttpStatusCode.InternalServerError, "MESSAGE_SEND_FAILED", "Mesaj gönderilemedi.")

        // Konuşma başına tek bildirim: UNIQUE kısıtı arka arkaya gelen
        // mesajlarda aynı satırı tazeliyor, bildirim yığılmıyor.
        repository.counterpartId(conversationId, viewerId)?.let { other ->
            notifications.emit(
                other, viewerId,
                NotificationType.MESSAGE, NotificationTargetType.CONVERSATION, conversationId,
            )
        }

        return details.toResponse(viewerId)
    }

    fun markRead(viewerId: UUID, conversationId: UUID): ReadReceiptResponse {
        requireMembership(conversationId, viewerId)
        val now = clock.instant()
        repository.markRead(conversationId, viewerId, now)

        return ReadReceiptResponse(
            conversationId = conversationId.toString(),
            unreadCount = repository.unreadCount(conversationId, viewerId),
            readAt = now.toString(),
        )
    }

    /** Mesajı yalnızca gönderen silebilir. */
    fun deleteMessage(viewerId: UUID, messageId: UUID) {
        if (!repository.deleteMessage(messageId, viewerId, clock.instant())) {
            throw ApiException(HttpStatusCode.NotFound, "MESSAGE_NOT_FOUND", "Mesaj bulunamadı.")
        }
    }

    // ----------------------------------------------------------- yardımcılar

    /**
     * Üye olmayan kişiye "yok" diyoruz; "erişim yok" demek konuşmanın varlığını
     * doğrulardı. Engel durumunda karşı taraf görünmediği için de aynı yola düşer.
     */
    private fun requireMembership(conversationId: UUID, viewerId: UUID) {
        if (!repository.isMember(conversationId, viewerId)) throw conversationNotFound()
        if (repository.counterpart(conversationId, viewerId) == null) throw conversationNotFound()
    }

    /** Medyayı doğrular ve MIME türünden mesaj tipini çıkarır. */
    private fun resolveMediaType(mediaId: UUID, ownerId: UUID): MessageType {
        val result = repository.checkMedia(mediaId, ownerId)
        when (result.check) {
            MessageMediaCheck.OK -> Unit
            // Başkasının medyasının varlığını açığa çıkarmamak için "yok" ile aynı kod.
            MessageMediaCheck.NOT_FOUND, MessageMediaCheck.NOT_OWNED ->
                throw validation("MEDIA_NOT_AVAILABLE", "Medya bulunamadı veya sana ait değil.", "mediaId")
            MessageMediaCheck.NOT_READY ->
                throw validation("MEDIA_NOT_READY", "Medya henüz kullanıma hazır değil.", "mediaId")
        }
        return if (result.mimeType?.startsWith("video/") == true) MessageType.VIDEO else MessageType.IMAGE
    }

    private fun MessageDetails.toResponse(viewerId: UUID): MessageResponse {
        val mine = message.senderId == viewerId
        return MessageResponse(
            id = message.id.toString(),
            conversationId = message.conversationId.toString(),
            sender = sender.withAvatar(storage),
            type = message.type.name,
            text = message.body,
            media = media?.let {
                MessageMediaResponse(
                    id = it.id.toString(),
                    mimeType = it.mimeType,
                    url = storage.createDownloadUrl(it.storageKey, mediaUrlExpiry),
                    urlExpiresInSeconds = mediaUrlExpiry.seconds,
                    durationSeconds = it.durationSeconds,
                    width = it.width,
                    height = it.height,
                )
            },
            createdAt = message.createdAt.toString(),
            mineByMe = mine,
            // "Görüldü" yalnızca kendi mesajın için anlamlı.
            seenByOther = mine && seenByOther,
        )
    }

    private fun conversationNotFound() =
        ApiException(HttpStatusCode.NotFound, "CONVERSATION_NOT_FOUND", "Konuşma bulunamadı.")

    companion object {
        const val MAX_TEXT_LENGTH = 4_000
        const val DEFAULT_PAGE_SIZE = 30
        const val MAX_PAGE_SIZE = 100

        internal fun encodeCursor(createdAt: Instant, id: UUID): String {
            val raw = "${createdAt.toEpochMilli()}|$id"
            return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.toByteArray(StandardCharsets.UTF_8))
        }

        internal fun decodeCursor(rawCursor: String): MessageCursor = try {
            val decoded = String(Base64.getUrlDecoder().decode(rawCursor), StandardCharsets.UTF_8)
            val parts = decoded.split('|', limit = 2)
            MessageCursor(Instant.ofEpochMilli(parts[0].toLong()), UUID.fromString(parts[1]))
        } catch (_: Throwable) {
            throw ApiException(HttpStatusCode.BadRequest, "INVALID_CURSOR", "Liste imleci geçersiz.", "cursor")
        }
    }
}
