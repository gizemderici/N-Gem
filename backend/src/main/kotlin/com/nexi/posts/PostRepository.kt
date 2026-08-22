package com.nexi.posts

import com.nexi.media.MediaAsset
import com.nexi.media.MediaStatus
import java.sql.Connection
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

interface PostRepository {
    fun create(ownerId: UUID, body: String, mediaIds: List<UUID>, now: Instant): Post
    fun findDetails(postId: UUID, viewerId: UUID): PostDetails?
    fun feed(viewerId: UUID, cursor: FeedCursor?, limit: Int): List<PostDetails>
    fun markDeleted(postId: UUID, ownerId: UUID, now: Instant): Boolean
    fun setLike(postId: UUID, userId: UUID, active: Boolean, now: Instant): Long
    fun setSave(postId: UUID, userId: UUID, active: Boolean, now: Instant): Long
}

class MediaOwnershipException : RuntimeException()
class MediaAlreadyAttachedException : RuntimeException()

class JdbcPostRepository(private val dataSource: DataSource) : PostRepository {
    override fun create(ownerId: UUID, body: String, mediaIds: List<UUID>, now: Instant): Post {
        val post = Post(UUID.randomUUID(), ownerId, body, PostStatus.PUBLISHED, now, now)
        dataSource.connection.use { connection ->
            connection.autoCommit = false
            try {
                validateMedia(connection, ownerId, mediaIds)
                connection.prepareStatement(
                    "INSERT INTO posts (id, owner_id, body, status, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?)"
                ).use { statement ->
                    statement.setObject(1, post.id)
                    statement.setObject(2, post.ownerId)
                    statement.setString(3, post.body)
                    statement.setString(4, post.status.name)
                    statement.setTimestamp(5, Timestamp.from(post.createdAt))
                    statement.setTimestamp(6, Timestamp.from(post.updatedAt))
                    statement.executeUpdate()
                }
                connection.prepareStatement(
                    "INSERT INTO post_media (post_id, media_id, position) VALUES (?, ?, ?)"
                ).use { statement ->
                    mediaIds.forEachIndexed { index, mediaId ->
                        statement.setObject(1, post.id)
                        statement.setObject(2, mediaId)
                        statement.setInt(3, index)
                        statement.addBatch()
                    }
                    if (mediaIds.isNotEmpty()) statement.executeBatch()
                }
                connection.commit()
            } catch (exception: Throwable) {
                connection.rollback()
                throw exception
            } finally {
                connection.autoCommit = true
            }
        }
        return post
    }

    override fun findDetails(postId: UUID, viewerId: UUID): PostDetails? = dataSource.connection.use { connection ->
        val details = connection.prepareStatement("${detailsSelect()} WHERE p.id = ? AND p.status = 'PUBLISHED'").use { statement ->
            statement.setObject(1, viewerId)
            statement.setObject(2, viewerId)
            statement.setObject(3, postId)
            statement.executeQuery().use { results ->
                if (results.next()) results.toDetails() else null
            }
        }
        details?.copy(media = connection.loadMedia(details.post.id))
    }

    override fun feed(viewerId: UUID, cursor: FeedCursor?, limit: Int): List<PostDetails> =
        dataSource.connection.use { connection ->
            val cursorClause = if (cursor == null) "" else "AND (p.created_at, p.id) < (?, ?)"
            connection.prepareStatement(
                "${detailsSelect()} WHERE p.status = 'PUBLISHED' $cursorClause ORDER BY p.created_at DESC, p.id DESC LIMIT ?"
            ).use { statement ->
                var index = 1
                statement.setObject(index++, viewerId)
                statement.setObject(index++, viewerId)
                if (cursor != null) {
                    statement.setTimestamp(index++, Timestamp.from(cursor.createdAt))
                    statement.setObject(index++, cursor.id)
                }
                statement.setInt(index, limit)
                val details = statement.executeQuery().use { results ->
                    buildList { while (results.next()) add(results.toDetails()) }
                }
                details.map { it.copy(media = connection.loadMedia(it.post.id)) }
            }
        }

    override fun markDeleted(postId: UUID, ownerId: UUID, now: Instant): Boolean = dataSource.connection.use { connection ->
        connection.prepareStatement(
            "UPDATE posts SET status = 'DELETED', updated_at = ? WHERE id = ? AND owner_id = ? AND status = 'PUBLISHED'"
        ).use { statement ->
            statement.setTimestamp(1, Timestamp.from(now))
            statement.setObject(2, postId)
            statement.setObject(3, ownerId)
            statement.executeUpdate() == 1
        }
    }

    override fun setLike(postId: UUID, userId: UUID, active: Boolean, now: Instant): Long =
        setInteraction("post_likes", postId, userId, active, now)

    override fun setSave(postId: UUID, userId: UUID, active: Boolean, now: Instant): Long =
        setInteraction("post_saves", postId, userId, active, now)

    private fun setInteraction(table: String, postId: UUID, userId: UUID, active: Boolean, now: Instant): Long =
        dataSource.connection.use { connection ->
            if (active) {
                connection.prepareStatement(
                    "INSERT INTO $table (post_id, user_id, created_at) VALUES (?, ?, ?) ON CONFLICT DO NOTHING"
                ).use { statement ->
                    statement.setObject(1, postId)
                    statement.setObject(2, userId)
                    statement.setTimestamp(3, Timestamp.from(now))
                    statement.executeUpdate()
                }
            } else {
                connection.prepareStatement("DELETE FROM $table WHERE post_id = ? AND user_id = ?").use { statement ->
                    statement.setObject(1, postId)
                    statement.setObject(2, userId)
                    statement.executeUpdate()
                }
            }
            connection.prepareStatement("SELECT COUNT(*) FROM $table WHERE post_id = ?").use { statement ->
                statement.setObject(1, postId)
                statement.executeQuery().use { results -> results.next(); results.getLong(1) }
            }
        }

    private fun validateMedia(connection: Connection, ownerId: UUID, mediaIds: List<UUID>) {
        if (mediaIds.isEmpty()) return
        val placeholders = mediaIds.joinToString(",") { "?" }
        connection.prepareStatement(
            """SELECT m.id, m.owner_id, m.status, pm.post_id
               FROM media_assets m LEFT JOIN post_media pm ON pm.media_id = m.id
               WHERE m.id IN ($placeholders) FOR UPDATE OF m"""
        ).use { statement ->
            mediaIds.forEachIndexed { index, id -> statement.setObject(index + 1, id) }
            val found = mutableSetOf<UUID>()
            statement.executeQuery().use { results ->
                while (results.next()) {
                    val id = results.getObject("id", UUID::class.java)
                    found += id
                    if (results.getObject("owner_id", UUID::class.java) != ownerId || results.getString("status") != "READY") {
                        throw MediaOwnershipException()
                    }
                    if (results.getObject("post_id") != null) throw MediaAlreadyAttachedException()
                }
            }
            if (found.size != mediaIds.size) throw MediaOwnershipException()
        }
    }

    private fun detailsSelect() =
        """SELECT p.id, p.owner_id, p.body, p.status, p.created_at, p.updated_at,
                  u.full_name, u.username,
                  (SELECT COUNT(*) FROM post_likes pl WHERE pl.post_id = p.id) AS like_count,
                  (SELECT COUNT(*) FROM post_saves ps WHERE ps.post_id = p.id) AS save_count,
                  EXISTS(SELECT 1 FROM post_likes pl WHERE pl.post_id = p.id AND pl.user_id = ?) AS liked_by_viewer,
                  EXISTS(SELECT 1 FROM post_saves ps WHERE ps.post_id = p.id AND ps.user_id = ?) AS saved_by_viewer
           FROM posts p JOIN users u ON u.id = p.owner_id"""

    private fun ResultSet.toDetails(): PostDetails {
        val post = Post(
            id = getObject("id", UUID::class.java),
            ownerId = getObject("owner_id", UUID::class.java),
            body = getString("body"),
            status = PostStatus.valueOf(getString("status")),
            createdAt = getTimestamp("created_at").toInstant(),
            updatedAt = getTimestamp("updated_at").toInstant(),
        )
        return PostDetails(
            post = post,
            author = PostAuthorResponse(
                id = post.ownerId.toString(),
                fullName = getString("full_name"),
                username = getString("username"),
            ),
            media = emptyList(),
            likeCount = getLong("like_count"),
            saveCount = getLong("save_count"),
            likedByViewer = getBoolean("liked_by_viewer"),
            savedByViewer = getBoolean("saved_by_viewer"),
        )
    }

    private fun Connection.loadMedia(postId: UUID): List<MediaAsset> = prepareStatement(
        """SELECT m.* FROM post_media pm JOIN media_assets m ON m.id = pm.media_id
           WHERE pm.post_id = ? ORDER BY pm.position"""
    ).use { statement ->
        statement.setObject(1, postId)
        statement.executeQuery().use { results ->
            buildList {
                while (results.next()) {
                    add(
                        MediaAsset(
                            id = results.getObject("id", UUID::class.java),
                            ownerId = results.getObject("owner_id", UUID::class.java),
                            storageKey = results.getString("storage_key"),
                            originalFilename = results.getString("original_filename"),
                            mimeType = results.getString("mime_type"),
                            declaredSizeBytes = results.getLong("declared_size_bytes"),
                            actualSizeBytes = results.getLong("actual_size_bytes").let { if (results.wasNull()) null else it },
                            status = MediaStatus.valueOf(results.getString("status")),
                            createdAt = results.getTimestamp("created_at").toInstant(),
                            updatedAt = results.getTimestamp("updated_at").toInstant(),
                        )
                    )
                }
            }
        }
    }
}
