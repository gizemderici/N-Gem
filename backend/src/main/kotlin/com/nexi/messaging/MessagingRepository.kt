package com.nexi.messaging

import com.nexi.media.MediaAsset
import com.nexi.media.MediaProcessingStatus
import com.nexi.media.MediaStatus
import com.nexi.moderation.BlockFilter
import com.nexi.posts.PostAuthorResponse
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

/** Mesaja eklenmek istenen medyanın kontrol sonucu. */
enum class MessageMediaCheck { OK, NOT_FOUND, NOT_OWNED, NOT_READY }

/**
 * Kontrol sonucu ve — geçtiyse — mesaj tipini belirleyen MIME türü.
 * Tipi çıkarabilmek için türü de taşımak gerekiyor.
 */
data class MessageMediaResult(val check: MessageMediaCheck, val mimeType: String? = null)

interface MessagingRepository {
    /** Bire bir konuşmayı getirir, yoksa oluşturur. */
    fun findOrCreateDirect(userId: UUID, otherId: UUID, now: Instant): UUID

    fun isMember(conversationId: UUID, userId: UUID): Boolean

    /** Konuşmadaki karşı taraf; engelliyse `null`. */
    fun counterpart(conversationId: UUID, viewerId: UUID): PostAuthorResponse?

    /** Karşı tarafın kimliği; engelden bağımsız, bildirim üretmek için. */
    fun counterpartId(conversationId: UUID, viewerId: UUID): UUID?

    fun conversations(viewerId: UUID, cursor: MessageCursor?, limit: Int): List<ConversationSummary>
    fun totalUnread(viewerId: UUID): Long

    fun messages(conversationId: UUID, viewerId: UUID, cursor: MessageCursor?, limit: Int): List<MessageDetails>
    fun findMessage(messageId: UUID, viewerId: UUID): MessageDetails?

    fun checkMedia(mediaId: UUID, ownerId: UUID): MessageMediaResult
    fun send(message: Message): MessageDetails?

    fun markRead(conversationId: UUID, userId: UUID, now: Instant)
    fun unreadCount(conversationId: UUID, userId: UUID): Long

    fun deleteMessage(messageId: UUID, senderId: UUID, now: Instant): Boolean
}

class JdbcMessagingRepository(private val dataSource: DataSource) : MessagingRepository {

    override fun findOrCreateDirect(userId: UUID, otherId: UUID, now: Instant): UUID {
        val key = directKey(userId, otherId)
        return dataSource.connection.use { connection ->
            connection.autoCommit = false
            try {
                val id = UUID.randomUUID()
                // Yarışta kaybeden istek de aynı konuşmayı alsın diye
                // ON CONFLICT ... DO NOTHING sonrası tekrar okuyoruz.
                connection.prepareStatement(
                    "INSERT INTO conversations (id, created_at, direct_key) VALUES (?, ?, ?) ON CONFLICT (direct_key) DO NOTHING"
                ).use { statement ->
                    statement.setObject(1, id)
                    statement.setTimestamp(2, Timestamp.from(now))
                    statement.setString(3, key)
                    statement.executeUpdate()
                }

                val conversationId = connection.prepareStatement(
                    "SELECT id FROM conversations WHERE direct_key = ?"
                ).use { statement ->
                    statement.setString(1, key)
                    statement.executeQuery().use { results ->
                        results.next()
                        results.getObject("id", UUID::class.java)
                    }
                }

                connection.prepareStatement(
                    "INSERT INTO conversation_members (conversation_id, user_id, joined_at) VALUES (?, ?, ?) ON CONFLICT DO NOTHING"
                ).use { statement ->
                    listOf(userId, otherId).forEach { member ->
                        statement.setObject(1, conversationId)
                        statement.setObject(2, member)
                        statement.setTimestamp(3, Timestamp.from(now))
                        statement.addBatch()
                    }
                    statement.executeBatch()
                }

                connection.commit()
                conversationId
            } catch (exception: Throwable) {
                connection.rollback()
                throw exception
            } finally {
                connection.autoCommit = true
            }
        }
    }

    override fun isMember(conversationId: UUID, userId: UUID): Boolean = dataSource.connection.use { connection ->
        connection.prepareStatement(
            "SELECT 1 FROM conversation_members WHERE conversation_id = ? AND user_id = ?"
        ).use { statement ->
            statement.setObject(1, conversationId)
            statement.setObject(2, userId)
            statement.executeQuery().use { it.next() }
        }
    }

    override fun counterpart(conversationId: UUID, viewerId: UUID): PostAuthorResponse? =
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """SELECT u.id, u.full_name, u.username, am.storage_key AS avatar_key,
                          EXISTS(SELECT 1 FROM follows f WHERE f.follower_id = ? AND f.followee_id = u.id) AS followed
                   FROM conversation_members cm
                   JOIN users u ON u.id = cm.user_id
                   LEFT JOIN media_assets am ON am.id = u.avatar_media_id AND am.status = 'READY'
                   WHERE cm.conversation_id = ? AND cm.user_id <> ? AND ${BlockFilter.notBlocked("u.id")}
                   LIMIT 1"""
            ).use { statement ->
                statement.setObject(1, viewerId)
                statement.setObject(2, conversationId)
                statement.setObject(3, viewerId)
                statement.setObject(4, viewerId)
                statement.setObject(5, viewerId)
                statement.executeQuery().use { results ->
                    if (results.next()) results.toAuthor() else null
                }
            }
        }

    override fun counterpartId(conversationId: UUID, viewerId: UUID): UUID? =
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                "SELECT user_id FROM conversation_members WHERE conversation_id = ? AND user_id <> ? LIMIT 1"
            ).use { statement ->
                statement.setObject(1, conversationId)
                statement.setObject(2, viewerId)
                statement.executeQuery().use { results ->
                    if (results.next()) results.getObject("user_id", UUID::class.java) else null
                }
            }
        }

    override fun conversations(viewerId: UUID, cursor: MessageCursor?, limit: Int): List<ConversationSummary> =
        dataSource.connection.use { connection ->
            val cursorClause = if (cursor == null) "" else "AND (c.last_message_at, c.id) < (?, ?)"
            connection.prepareStatement(
                """SELECT c.id AS conversation_id, c.last_message_at,
                          u.id AS other_id, u.full_name, u.username, am.storage_key AS avatar_key,
                          EXISTS(SELECT 1 FROM follows f WHERE f.follower_id = ? AND f.followee_id = u.id) AS followed,
                          (SELECT COUNT(*) FROM messages m
                            WHERE m.conversation_id = c.id AND m.status = 'SENT' AND m.sender_id <> ?
                              AND (me.last_read_at IS NULL OR m.created_at > me.last_read_at)) AS unread_count
                   FROM conversation_members me
                   JOIN conversations c ON c.id = me.conversation_id
                   JOIN conversation_members other ON other.conversation_id = c.id AND other.user_id <> me.user_id
                   JOIN users u ON u.id = other.user_id
                   LEFT JOIN media_assets am ON am.id = u.avatar_media_id AND am.status = 'READY'
                   WHERE me.user_id = ? AND c.last_message_at IS NOT NULL
                     AND ${BlockFilter.notBlocked("u.id")} $cursorClause
                   ORDER BY c.last_message_at DESC, c.id DESC LIMIT ?"""
            ).use { statement ->
                var index = 1
                statement.setObject(index++, viewerId)
                statement.setObject(index++, viewerId)
                statement.setObject(index++, viewerId)
                repeat(BlockFilter.BINDINGS) { statement.setObject(index++, viewerId) }
                if (cursor != null) {
                    statement.setTimestamp(index++, Timestamp.from(cursor.createdAt))
                    statement.setObject(index++, cursor.id)
                }
                statement.setInt(index, limit)
                statement.executeQuery().use { results ->
                    buildList {
                        while (results.next()) {
                            add(
                                ConversationSummary(
                                    id = results.getObject("conversation_id", UUID::class.java),
                                    other = results.toAuthor(idColumn = "other_id"),
                                    lastMessage = null,
                                    unreadCount = results.getLong("unread_count"),
                                    lastMessageAt = results.getTimestamp("last_message_at")?.toInstant(),
                                )
                            )
                        }
                    }
                }
            }
        }

    override fun totalUnread(viewerId: UUID): Long = dataSource.connection.use { connection ->
        connection.prepareStatement(
            """SELECT COUNT(*) FROM messages m
               JOIN conversation_members me ON me.conversation_id = m.conversation_id AND me.user_id = ?
               JOIN conversation_members other ON other.conversation_id = m.conversation_id AND other.user_id <> me.user_id
               WHERE m.status = 'SENT' AND m.sender_id <> ?
                 AND (me.last_read_at IS NULL OR m.created_at > me.last_read_at)
                 AND ${BlockFilter.notBlocked("other.user_id")}"""
        ).use { statement ->
            var index = 1
            statement.setObject(index++, viewerId)
            statement.setObject(index++, viewerId)
            repeat(BlockFilter.BINDINGS) { statement.setObject(index++, viewerId) }
            statement.executeQuery().use { results -> results.next(); results.getLong(1) }
        }
    }

    override fun messages(
        conversationId: UUID,
        viewerId: UUID,
        cursor: MessageCursor?,
        limit: Int,
    ): List<MessageDetails> = dataSource.connection.use { connection ->
        val cursorClause = if (cursor == null) "" else "AND (m.created_at, m.id) < (?, ?)"
        connection.prepareStatement(
            "${detailsSelect()} WHERE m.conversation_id = ? AND m.status = 'SENT' $cursorClause " +
                "ORDER BY m.created_at DESC, m.id DESC LIMIT ?"
        ).use { statement ->
            var index = 1
            // detailsSelect tek kez viewerId istiyor: gönderenin takip durumu.
            statement.setObject(index++, viewerId)
            statement.setObject(index++, conversationId)
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

    override fun findMessage(messageId: UUID, viewerId: UUID): MessageDetails? =
        dataSource.connection.use { connection ->
            connection.prepareStatement("${detailsSelect()} WHERE m.id = ? AND m.status = 'SENT'").use { statement ->
                statement.setObject(1, viewerId)
                statement.setObject(2, messageId)
                statement.executeQuery().use { results ->
                    if (results.next()) results.toDetails() else null
                }
            }
        }

    override fun checkMedia(mediaId: UUID, ownerId: UUID): MessageMediaResult =
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                "SELECT owner_id, status, processing_status, mime_type FROM media_assets WHERE id = ?"
            ).use { statement ->
                statement.setObject(1, mediaId)
                statement.executeQuery().use { results ->
                    if (!results.next()) return@use MessageMediaResult(MessageMediaCheck.NOT_FOUND)
                    val check = when {
                        results.getObject("owner_id", UUID::class.java) != ownerId -> MessageMediaCheck.NOT_OWNED
                        results.getString("status") != MediaStatus.READY.name -> MessageMediaCheck.NOT_READY
                        results.getString("processing_status") != MediaProcessingStatus.READY.name ->
                            MessageMediaCheck.NOT_READY
                        else -> MessageMediaCheck.OK
                    }
                    MessageMediaResult(check, results.getString("mime_type"))
                }
            }
        }

    override fun send(message: Message): MessageDetails? = dataSource.connection.use { connection ->
        connection.autoCommit = false
        try {
            connection.prepareStatement(
                """INSERT INTO messages
                   (id, conversation_id, sender_id, type, body, media_id, status, created_at, updated_at)
                   VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)"""
            ).use { statement ->
                statement.setObject(1, message.id)
                statement.setObject(2, message.conversationId)
                statement.setObject(3, message.senderId)
                statement.setString(4, message.type.name)
                statement.setString(5, message.body)
                statement.setObject(6, message.mediaId)
                statement.setString(7, message.status.name)
                statement.setTimestamp(8, Timestamp.from(message.createdAt))
                statement.setTimestamp(9, Timestamp.from(message.createdAt))
                statement.executeUpdate()
            }
            connection.prepareStatement(
                "UPDATE conversations SET last_message_at = ? WHERE id = ?"
            ).use { statement ->
                statement.setTimestamp(1, Timestamp.from(message.createdAt))
                statement.setObject(2, message.conversationId)
                statement.executeUpdate()
            }
            connection.commit()
        } catch (exception: Throwable) {
            connection.rollback()
            throw exception
        } finally {
            connection.autoCommit = true
        }
        findMessage(message.id, message.senderId)
    }

    override fun markRead(conversationId: UUID, userId: UUID, now: Instant) {
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                "UPDATE conversation_members SET last_read_at = ? WHERE conversation_id = ? AND user_id = ?"
            ).use { statement ->
                statement.setTimestamp(1, Timestamp.from(now))
                statement.setObject(2, conversationId)
                statement.setObject(3, userId)
                statement.executeUpdate()
            }
        }
    }

    override fun unreadCount(conversationId: UUID, userId: UUID): Long = dataSource.connection.use { connection ->
        connection.prepareStatement(
            """SELECT COUNT(*) FROM messages m
               JOIN conversation_members me ON me.conversation_id = m.conversation_id AND me.user_id = ?
               WHERE m.conversation_id = ? AND m.status = 'SENT' AND m.sender_id <> ?
                 AND (me.last_read_at IS NULL OR m.created_at > me.last_read_at)"""
        ).use { statement ->
            statement.setObject(1, userId)
            statement.setObject(2, conversationId)
            statement.setObject(3, userId)
            statement.executeQuery().use { results -> results.next(); results.getLong(1) }
        }
    }

    override fun deleteMessage(messageId: UUID, senderId: UUID, now: Instant): Boolean =
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                "UPDATE messages SET status = 'DELETED', updated_at = ? WHERE id = ? AND sender_id = ? AND status = 'SENT'"
            ).use { statement ->
                statement.setTimestamp(1, Timestamp.from(now))
                statement.setObject(2, messageId)
                statement.setObject(3, senderId)
                statement.executeUpdate() == 1
            }
        }

    /**
     * "Görüldü" bilgisi karşı tarafın okundu su seviyesinden geliyor; mesaj
     * başına ayrı bir okuma satırı tutmaya gerek yok.
     */
    private fun detailsSelect() =
        """SELECT m.id, m.conversation_id, m.sender_id, m.type, m.body, m.media_id, m.status, m.created_at,
                  u.full_name, u.username, am.storage_key AS avatar_key,
                  EXISTS(SELECT 1 FROM follows f WHERE f.follower_id = ? AND f.followee_id = u.id) AS followed,
                  EXISTS(SELECT 1 FROM conversation_members o
                          WHERE o.conversation_id = m.conversation_id AND o.user_id <> m.sender_id
                            AND o.last_read_at IS NOT NULL AND o.last_read_at >= m.created_at) AS seen_by_other,
                  mm.id AS media_pk, mm.owner_id AS media_owner, mm.storage_key, mm.original_filename,
                  mm.mime_type, mm.declared_size_bytes, mm.actual_size_bytes, mm.status AS media_status,
                  mm.created_at AS media_created, mm.updated_at AS media_updated,
                  mm.processing_status, mm.duration_seconds, mm.width, mm.height
           FROM messages m
           JOIN users u ON u.id = m.sender_id
           LEFT JOIN media_assets am ON am.id = u.avatar_media_id AND am.status = 'READY'
           LEFT JOIN media_assets mm ON mm.id = m.media_id"""

    private fun directKey(a: UUID, b: UUID): String =
        if (a.toString() < b.toString()) "$a:$b" else "$b:$a"

    private fun ResultSet.toAuthor(idColumn: String = "id") = PostAuthorResponse(
        id = getObject(idColumn, UUID::class.java).toString(),
        fullName = getString("full_name"),
        username = getString("username"),
        followedByMe = getBoolean("followed"),
        avatarStorageKey = getString("avatar_key"),
    )

    private fun ResultSet.toDetails(): MessageDetails {
        val senderId = getObject("sender_id", UUID::class.java)
        val mediaPk = getObject("media_pk", UUID::class.java)
        return MessageDetails(
            message = Message(
                id = getObject("id", UUID::class.java),
                conversationId = getObject("conversation_id", UUID::class.java),
                senderId = senderId,
                type = MessageType.valueOf(getString("type")),
                body = getString("body"),
                mediaId = getObject("media_id", UUID::class.java),
                status = MessageStatus.valueOf(getString("status")),
                createdAt = getTimestamp("created_at").toInstant(),
            ),
            sender = PostAuthorResponse(
                id = senderId.toString(),
                fullName = getString("full_name"),
                username = getString("username"),
                followedByMe = getBoolean("followed"),
                avatarStorageKey = getString("avatar_key"),
            ),
            media = mediaPk?.let {
                MediaAsset(
                    id = it,
                    ownerId = getObject("media_owner", UUID::class.java),
                    storageKey = getString("storage_key"),
                    originalFilename = getString("original_filename"),
                    mimeType = getString("mime_type"),
                    declaredSizeBytes = getLong("declared_size_bytes"),
                    actualSizeBytes = getLong("actual_size_bytes").let { size -> if (wasNull()) null else size },
                    status = MediaStatus.valueOf(getString("media_status")),
                    createdAt = getTimestamp("media_created").toInstant(),
                    updatedAt = getTimestamp("media_updated").toInstant(),
                    processingStatus = MediaProcessingStatus.valueOf(getString("processing_status")),
                    durationSeconds = getDouble("duration_seconds").let { d -> if (wasNull()) null else d },
                    width = getInt("width").let { w -> if (wasNull()) null else w },
                    height = getInt("height").let { h -> if (wasNull()) null else h },
                )
            },
            seenByOther = getBoolean("seen_by_other"),
        )
    }
}
