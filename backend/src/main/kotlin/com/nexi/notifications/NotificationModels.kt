package com.nexi.notifications

import com.nexi.posts.PostAuthorResponse
import kotlinx.serialization.Serializable
import java.time.Instant
import java.util.UUID

/**
 * Bugün üretilen bildirim türleri.
 *
 * Planda geçen "yorum cevabı", "hikâye etkileşimi" ve "şikâyet sonucu"
 * türleri henüz yok, çünkü dayandıkları özellikler yok: yorumlarda iç içe
 * yanıt (Faz 4'te bilinçli olarak kapsam dışı), hikâyelerde tepki (yalnızca
 * görüntüleme var ve görüntüleyen listesi zaten mevcut), moderatör uçları
 * (Faz 9'da sonraya bırakıldı). Bu türler o akışlar geldiğinde eklenecek.
 */
enum class NotificationType { FOLLOW, POST_LIKE, POST_COMMENT, MESSAGE, SYSTEM }

enum class NotificationTargetType { POST, COMMENT, STORY, MESSAGE, USER, CONVERSATION }

data class Notification(
    val id: UUID,
    val userId: UUID,
    val actorId: UUID?,
    val type: NotificationType,
    val targetType: NotificationTargetType?,
    val targetId: UUID?,
    val readAt: Instant?,
    val createdAt: Instant,
)

data class NotificationDetails(
    val notification: Notification,
    /** Sistem bildirimlerinde boş. */
    val actor: PostAuthorResponse?,
)

data class NotificationCursor(val createdAt: Instant, val id: UUID)

@Serializable
data class NotificationResponse(
    val id: String,
    val type: String,
    val actor: PostAuthorResponse? = null,
    val targetType: String? = null,
    val targetId: String? = null,
    val read: Boolean,
    val createdAt: String,
)

@Serializable
data class NotificationPageResponse(
    val items: List<NotificationResponse>,
    val nextCursor: String? = null,
    val unreadCount: Long,
)

@Serializable
data class UnreadCountResponse(val unreadCount: Long)

@Serializable
data class NotificationReadResponse(
    val id: String? = null,
    val unreadCount: Long,
)
