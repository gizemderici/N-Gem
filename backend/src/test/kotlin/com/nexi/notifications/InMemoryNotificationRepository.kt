package com.nexi.notifications

import com.nexi.posts.PostAuthorResponse
import java.time.Instant
import java.util.UUID

internal class InMemoryNotificationRepository(private val baseTime: Instant) : NotificationRepository {
    private data class Key(
        val userId: UUID,
        val actorId: UUID?,
        val type: NotificationType,
        val targetType: NotificationTargetType?,
        val targetId: UUID?,
    )

    private val rows = linkedMapOf<Key, Notification>()
    private val users = mutableMapOf<UUID, Pair<String, String>>()

    val blocks = mutableSetOf<Pair<UUID, UUID>>()

    private var sequence = 0L

    fun addUser(fullName: String, username: String): UUID =
        UUID.randomUUID().also { users[it] = fullName to username }

    private fun blocked(a: UUID, b: UUID) = (a to b) in blocks || (b to a) in blocks

    private fun key(n: Notification) = Key(n.userId, n.actorId, n.type, n.targetType, n.targetId)

    override fun emit(notification: Notification) {
        // Her yeni olay bir saniye sonraya dussun ki siralama belirli olsun.
        val stamped = notification.copy(createdAt = baseTime.plusSeconds(sequence++))
        val k = key(stamped)
        val existing = rows[k]
        rows[k] = if (existing == null) {
            stamped
        } else {
            // Gercek kisit gibi: satir tazelenir ve okunmamisa doner.
            existing.copy(createdAt = stamped.createdAt, readAt = null)
        }
    }

    override fun page(userId: UUID, cursor: NotificationCursor?, limit: Int): List<NotificationDetails> = rows.values
        .filter { it.userId == userId }
        .filterNot { it.actorId != null && blocked(userId, it.actorId) }
        .sortedWith(compareByDescending<Notification> { it.createdAt }.thenByDescending { it.id })
        .filter {
            cursor == null ||
                it.createdAt < cursor.createdAt ||
                (it.createdAt == cursor.createdAt && it.id < cursor.id)
        }
        .take(limit)
        .map(::details)

    override fun unreadCount(userId: UUID): Long = rows.values
        .filter { it.userId == userId && it.readAt == null }
        .filterNot { it.actorId != null && blocked(userId, it.actorId) }
        .size.toLong()

    override fun markRead(userId: UUID, notificationId: UUID, now: Instant): Boolean {
        val entry = rows.entries.firstOrNull { it.value.id == notificationId && it.value.userId == userId }
            ?: return false
        if (entry.value.readAt != null) return false
        rows[entry.key] = entry.value.copy(readAt = now)
        return true
    }

    override fun markAllRead(userId: UUID, now: Instant) {
        rows.entries
            .filter { it.value.userId == userId && it.value.readAt == null }
            .forEach { rows[it.key] = it.value.copy(readAt = now) }
    }

    override fun delete(userId: UUID, notificationId: UUID): Boolean {
        val entry = rows.entries.firstOrNull { it.value.id == notificationId && it.value.userId == userId }
            ?: return false
        rows.remove(entry.key)
        return true
    }

    override fun deleteOlderThan(cutoff: Instant, limit: Int): Int {
        val doomed = rows.entries.filter { it.value.createdAt < cutoff }.take(limit)
        doomed.forEach { rows.remove(it.key) }
        return doomed.size
    }

    private fun details(notification: Notification) = NotificationDetails(
        notification = notification,
        actor = notification.actorId?.let { id ->
            val row = users[id]
            PostAuthorResponse(
                id = id.toString(),
                fullName = row?.first ?: "Bilinmeyen",
                username = row?.second ?: "bilinmeyen",
            )
        },
    )
}
