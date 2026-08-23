package com.nexi.notifications

import com.nexi.moderation.BlockFilter
import com.nexi.posts.PostAuthorResponse
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

interface NotificationRepository {
    /**
     * Bildirimi yazar. Aynı (alıcı, aktör, tür, hedef) dörtlüsü zaten varsa
     * yeni satır açmaz; mevcut satırı tazeler ve okunmamışa döndürür.
     */
    fun emit(notification: Notification)

    fun page(userId: UUID, cursor: NotificationCursor?, limit: Int): List<NotificationDetails>
    fun unreadCount(userId: UUID): Long

    fun markRead(userId: UUID, notificationId: UUID, now: Instant): Boolean
    fun markAllRead(userId: UUID, now: Instant)
    fun delete(userId: UUID, notificationId: UUID): Boolean

    /** Saklama süresi dolmuş bildirimleri siler. */
    fun deleteOlderThan(cutoff: Instant, limit: Int): Int
}

class JdbcNotificationRepository(private val dataSource: DataSource) : NotificationRepository {

    override fun emit(notification: Notification) {
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """INSERT INTO notifications (id, user_id, actor_id, type, target_type, target_id, read_at, created_at)
                   VALUES (?, ?, ?, ?, ?, ?, NULL, ?)
                   ON CONFLICT ON CONSTRAINT notifications_unique_event
                   DO UPDATE SET created_at = EXCLUDED.created_at, read_at = NULL"""
            ).use { statement ->
                statement.setObject(1, notification.id)
                statement.setObject(2, notification.userId)
                statement.setObject(3, notification.actorId)
                statement.setString(4, notification.type.name)
                statement.setString(5, notification.targetType?.name)
                statement.setObject(6, notification.targetId)
                statement.setTimestamp(7, Timestamp.from(notification.createdAt))
                statement.executeUpdate()
            }
        }
    }

    override fun page(userId: UUID, cursor: NotificationCursor?, limit: Int): List<NotificationDetails> =
        dataSource.connection.use { connection ->
            val cursorClause = if (cursor == null) "" else "AND (n.created_at, n.id) < (?, ?)"
            connection.prepareStatement(
                """SELECT n.id, n.user_id, n.actor_id, n.type, n.target_type, n.target_id, n.read_at, n.created_at,
                          u.full_name, u.username, am.storage_key AS avatar_key,
                          EXISTS(SELECT 1 FROM follows f WHERE f.follower_id = ? AND f.followee_id = n.actor_id) AS followed
                   FROM notifications n
                   LEFT JOIN users u ON u.id = n.actor_id
                   LEFT JOIN media_assets am ON am.id = u.avatar_media_id AND am.status = 'READY'
                   WHERE n.user_id = ?
                     AND (n.actor_id IS NULL OR ${BlockFilter.notBlocked("n.actor_id")})
                     $cursorClause
                   ORDER BY n.created_at DESC, n.id DESC LIMIT ?"""
            ).use { statement ->
                var index = 1
                statement.setObject(index++, userId)
                statement.setObject(index++, userId)
                repeat(BlockFilter.BINDINGS) { statement.setObject(index++, userId) }
                if (cursor != null) {
                    statement.setTimestamp(index++, Timestamp.from(cursor.createdAt))
                    statement.setObject(index++, cursor.id)
                }
                statement.setInt(index, limit)
                statement.executeQuery().use { results ->
                    buildList { while (results.next()) add(results.toDetails()) }
                }
            }
        }

    override fun unreadCount(userId: UUID): Long = dataSource.connection.use { connection ->
        connection.prepareStatement(
            """SELECT COUNT(*) FROM notifications n
               WHERE n.user_id = ? AND n.read_at IS NULL
                 AND (n.actor_id IS NULL OR ${BlockFilter.notBlocked("n.actor_id")})"""
        ).use { statement ->
            var index = 1
            statement.setObject(index++, userId)
            repeat(BlockFilter.BINDINGS) { statement.setObject(index++, userId) }
            statement.executeQuery().use { results -> results.next(); results.getLong(1) }
        }
    }

    override fun markRead(userId: UUID, notificationId: UUID, now: Instant): Boolean =
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                "UPDATE notifications SET read_at = ? WHERE id = ? AND user_id = ? AND read_at IS NULL"
            ).use { statement ->
                statement.setTimestamp(1, Timestamp.from(now))
                statement.setObject(2, notificationId)
                statement.setObject(3, userId)
                statement.executeUpdate() == 1
            }
        }

    override fun markAllRead(userId: UUID, now: Instant) {
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                "UPDATE notifications SET read_at = ? WHERE user_id = ? AND read_at IS NULL"
            ).use { statement ->
                statement.setTimestamp(1, Timestamp.from(now))
                statement.setObject(2, userId)
                statement.executeUpdate()
            }
        }
    }

    override fun delete(userId: UUID, notificationId: UUID): Boolean = dataSource.connection.use { connection ->
        connection.prepareStatement("DELETE FROM notifications WHERE id = ? AND user_id = ?").use { statement ->
            statement.setObject(1, notificationId)
            statement.setObject(2, userId)
            statement.executeUpdate() == 1
        }
    }

    override fun deleteOlderThan(cutoff: Instant, limit: Int): Int = dataSource.connection.use { connection ->
        connection.prepareStatement(
            """DELETE FROM notifications
               WHERE id IN (SELECT id FROM notifications WHERE created_at < ? ORDER BY created_at LIMIT ?)"""
        ).use { statement ->
            statement.setTimestamp(1, Timestamp.from(cutoff))
            statement.setInt(2, limit)
            statement.executeUpdate()
        }
    }

    private fun ResultSet.toDetails(): NotificationDetails {
        val actorId = getObject("actor_id", UUID::class.java)
        return NotificationDetails(
            notification = Notification(
                id = getObject("id", UUID::class.java),
                userId = getObject("user_id", UUID::class.java),
                actorId = actorId,
                type = NotificationType.valueOf(getString("type")),
                targetType = getString("target_type")?.let(NotificationTargetType::valueOf),
                targetId = getObject("target_id", UUID::class.java),
                readAt = getTimestamp("read_at")?.toInstant(),
                createdAt = getTimestamp("created_at").toInstant(),
            ),
            actor = actorId?.let {
                PostAuthorResponse(
                    id = it.toString(),
                    fullName = getString("full_name"),
                    username = getString("username"),
                    followedByMe = getBoolean("followed"),
                    avatarStorageKey = getString("avatar_key"),
                )
            },
        )
    }
}
