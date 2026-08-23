package com.nexi.notifications

import com.nexi.auth.ApiException
import com.nexi.media.ObjectStorage
import com.nexi.posts.withAvatar
import io.ktor.http.HttpStatusCode
import org.slf4j.LoggerFactory
import java.nio.charset.StandardCharsets
import java.time.Clock
import java.time.Instant
import java.util.Base64
import java.util.UUID

/**
 * Bildirim üretmenin dar arayüzü.
 *
 * Servisler (takip, beğeni, yorum, mesaj) bildirimin nasıl saklandığını
 * bilmesin diye bu arayüzü alıyor. Varsayılanı hiçbir şey yapmıyor, böylece
 * bildirimle ilgilenmeyen testler değişmek zorunda kalmıyor.
 */
fun interface NotificationSink {
    fun emit(
        recipientId: UUID,
        actorId: UUID?,
        type: NotificationType,
        targetType: NotificationTargetType?,
        targetId: UUID?,
    )

    companion object {
        val NOOP = NotificationSink { _, _, _, _, _ -> }
    }
}

class NotificationService(
    private val repository: NotificationRepository,
    private val storage: ObjectStorage,
    private val clock: Clock = Clock.systemUTC(),
) : NotificationSink {
    private val logger = LoggerFactory.getLogger(NotificationService::class.java)

    /**
     * Bildirim yazar. Kendi eylemin için bildirim üretilmez.
     *
     * Yazma sırasında bir hata olursa asıl işlem (beğeni, yorum, mesaj)
     * düşmesin diye yutuluyor: bildirim ikincil bir yan etki.
     */
    override fun emit(
        recipientId: UUID,
        actorId: UUID?,
        type: NotificationType,
        targetType: NotificationTargetType?,
        targetId: UUID?,
    ) {
        if (actorId == recipientId) return

        runCatching {
            repository.emit(
                Notification(
                    id = UUID.randomUUID(),
                    userId = recipientId,
                    actorId = actorId,
                    type = type,
                    targetType = targetType,
                    targetId = targetId,
                    readAt = null,
                    createdAt = clock.instant(),
                )
            )
        }.onFailure { logger.warn("Notification could not be stored: type={}", type, it) }
    }

    fun page(userId: UUID, rawCursor: String?, requestedLimit: Int?): NotificationPageResponse {
        val limit = (requestedLimit ?: DEFAULT_PAGE_SIZE).coerceIn(1, MAX_PAGE_SIZE)
        val rows = repository.page(userId, rawCursor?.let { decodeCursor(it) }, limit + 1)
        val hasMore = rows.size > limit
        val items = rows.take(limit)

        return NotificationPageResponse(
            items = items.map { it.toResponse() },
            nextCursor = if (hasMore) {
                items.lastOrNull()?.let { encodeCursor(it.notification.createdAt, it.notification.id) }
            } else {
                null
            },
            unreadCount = repository.unreadCount(userId),
        )
    }

    fun unreadCount(userId: UUID) = UnreadCountResponse(repository.unreadCount(userId))

    fun markRead(userId: UUID, notificationId: UUID): NotificationReadResponse {
        // Zaten okunmuş bildirimi tekrar okundu işaretlemek hata değil; sonuç aynı.
        repository.markRead(userId, notificationId, clock.instant())
        return NotificationReadResponse(notificationId.toString(), repository.unreadCount(userId))
    }

    fun markAllRead(userId: UUID): NotificationReadResponse {
        repository.markAllRead(userId, clock.instant())
        return NotificationReadResponse(unreadCount = repository.unreadCount(userId))
    }

    fun delete(userId: UUID, notificationId: UUID) {
        if (!repository.delete(userId, notificationId)) {
            throw ApiException(HttpStatusCode.NotFound, "NOTIFICATION_NOT_FOUND", "Bildirim bulunamadı.")
        }
    }

    private fun NotificationDetails.toResponse() = NotificationResponse(
        id = notification.id.toString(),
        type = notification.type.name,
        actor = actor?.withAvatar(storage),
        targetType = notification.targetType?.name,
        targetId = notification.targetId?.toString(),
        read = notification.readAt != null,
        createdAt = notification.createdAt.toString(),
    )

    companion object {
        const val DEFAULT_PAGE_SIZE = 30
        const val MAX_PAGE_SIZE = 100

        internal fun encodeCursor(createdAt: Instant, id: UUID): String {
            val raw = "${createdAt.toEpochMilli()}|$id"
            return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.toByteArray(StandardCharsets.UTF_8))
        }

        internal fun decodeCursor(rawCursor: String): NotificationCursor = try {
            val decoded = String(Base64.getUrlDecoder().decode(rawCursor), StandardCharsets.UTF_8)
            val parts = decoded.split('|', limit = 2)
            NotificationCursor(Instant.ofEpochMilli(parts[0].toLong()), UUID.fromString(parts[1]))
        } catch (_: Throwable) {
            throw ApiException(HttpStatusCode.BadRequest, "INVALID_CURSOR", "Liste imleci geçersiz.", "cursor")
        }
    }
}
