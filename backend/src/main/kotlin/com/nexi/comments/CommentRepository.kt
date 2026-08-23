package com.nexi.comments

import com.nexi.posts.PostAuthorResponse
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

interface CommentRepository {
    fun create(comment: Comment): CommentDetails?

    /**
     * Yorumlar eskiden yeniye sıralanır: sohbet yukarıdan aşağıya okunur.
     * [viewerId] yazarların takip durumunu hesaplamak için gerekiyor.
     */
    fun page(postId: UUID, viewerId: UUID, cursor: CommentCursor?, limit: Int): List<CommentDetails>

    fun countForPost(postId: UUID): Long

    fun findById(commentId: UUID): Comment?

    /** Gönderinin sahibi; silme yetkisini kontrol etmek için. */
    fun postOwner(postId: UUID): UUID?

    fun markDeleted(commentId: UUID, now: Instant): Boolean
}

class JdbcCommentRepository(private val dataSource: DataSource) : CommentRepository {

    /** Gönderi yoksa ya da silinmişse `null` döner; yoruma izin verilmez. */
    override fun create(comment: Comment): CommentDetails? = dataSource.connection.use { connection ->
        val postExists = connection.prepareStatement(
            "SELECT 1 FROM posts WHERE id = ? AND status = 'PUBLISHED'"
        ).use { statement ->
            statement.setObject(1, comment.postId)
            statement.executeQuery().use { it.next() }
        }
        if (!postExists) return@use null

        connection.prepareStatement(
            """INSERT INTO comments (id, post_id, author_id, body, status, created_at, updated_at)
               VALUES (?, ?, ?, ?, ?, ?, ?)"""
        ).use { statement ->
            statement.setObject(1, comment.id)
            statement.setObject(2, comment.postId)
            statement.setObject(3, comment.authorId)
            statement.setString(4, comment.body)
            statement.setString(5, comment.status.name)
            statement.setTimestamp(6, Timestamp.from(comment.createdAt))
            statement.setTimestamp(7, Timestamp.from(comment.updatedAt))
            statement.executeUpdate()
        }

        connection.prepareStatement("${detailsSelect()} WHERE c.id = ?").use { statement ->
            // Yazar kendisi olduğu için takip durumu her zaman false.
            statement.setObject(1, comment.authorId)
            statement.setObject(2, comment.id)
            statement.executeQuery().use { results ->
                if (results.next()) results.toDetails() else null
            }
        }
    }

    override fun page(postId: UUID, viewerId: UUID, cursor: CommentCursor?, limit: Int): List<CommentDetails> =
        dataSource.connection.use { connection ->
            val cursorClause = if (cursor == null) "" else "AND (c.created_at, c.id) > (?, ?)"
            connection.prepareStatement(
                "${detailsSelect()} WHERE c.post_id = ? AND c.status = 'PUBLISHED' $cursorClause " +
                    "ORDER BY c.created_at, c.id LIMIT ?"
            ).use { statement ->
                var index = 1
                statement.setObject(index++, viewerId)
                statement.setObject(index++, postId)
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

    override fun countForPost(postId: UUID): Long = dataSource.connection.use { connection ->
        connection.prepareStatement(
            "SELECT COUNT(*) FROM comments WHERE post_id = ? AND status = 'PUBLISHED'"
        ).use { statement ->
            statement.setObject(1, postId)
            statement.executeQuery().use { results -> results.next(); results.getLong(1) }
        }
    }

    override fun findById(commentId: UUID): Comment? = dataSource.connection.use { connection ->
        connection.prepareStatement(
            "SELECT id, post_id, author_id, body, status, created_at, updated_at FROM comments WHERE id = ?"
        ).use { statement ->
            statement.setObject(1, commentId)
            statement.executeQuery().use { results -> if (results.next()) results.toComment() else null }
        }
    }

    override fun postOwner(postId: UUID): UUID? = dataSource.connection.use { connection ->
        connection.prepareStatement("SELECT owner_id FROM posts WHERE id = ?").use { statement ->
            statement.setObject(1, postId)
            statement.executeQuery().use { results ->
                if (results.next()) results.getObject("owner_id", UUID::class.java) else null
            }
        }
    }

    override fun markDeleted(commentId: UUID, now: Instant): Boolean = dataSource.connection.use { connection ->
        connection.prepareStatement(
            "UPDATE comments SET status = 'DELETED', updated_at = ? WHERE id = ? AND status = 'PUBLISHED'"
        ).use { statement ->
            statement.setTimestamp(1, Timestamp.from(now))
            statement.setObject(2, commentId)
            statement.executeUpdate() == 1
        }
    }

    private fun detailsSelect() =
        """SELECT c.id, c.post_id, c.author_id, c.body, c.status, c.created_at, c.updated_at,
                  u.full_name, u.username,
                  EXISTS(SELECT 1 FROM follows f WHERE f.follower_id = ? AND f.followee_id = c.author_id) AS author_followed,
                  am.storage_key AS author_avatar_key
           FROM comments c
           JOIN users u ON u.id = c.author_id
           LEFT JOIN media_assets am ON am.id = u.avatar_media_id AND am.status = 'READY'"""

    private fun ResultSet.toComment() = Comment(
        id = getObject("id", UUID::class.java),
        postId = getObject("post_id", UUID::class.java),
        authorId = getObject("author_id", UUID::class.java),
        body = getString("body"),
        status = CommentStatus.valueOf(getString("status")),
        createdAt = getTimestamp("created_at").toInstant(),
        updatedAt = getTimestamp("updated_at").toInstant(),
    )

    private fun ResultSet.toDetails(): CommentDetails {
        val comment = toComment()
        return CommentDetails(
            comment = comment,
            author = PostAuthorResponse(
                id = comment.authorId.toString(),
                fullName = getString("full_name"),
                username = getString("username"),
                followedByMe = getBoolean("author_followed"),
                avatarStorageKey = getString("author_avatar_key"),
            ),
        )
    }
}
