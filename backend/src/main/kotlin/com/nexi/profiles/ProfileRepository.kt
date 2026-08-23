package com.nexi.profiles

import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

/** Bir takip ilişkisindeki karşı taraf ve ilişkinin kurulma anı. */
data class FollowEdge(
    val userId: UUID,
    val fullName: String,
    val username: String,
    val avatarStorageKey: String?,
    val followedByViewer: Boolean,
    val createdAt: Instant,
)

/** Avatar olarak seçilen medyanın sahiplik ve durum kontrolü sonucu. */
enum class AvatarMediaCheck { OK, NOT_FOUND, NOT_OWNED, NOT_READY, NOT_AN_IMAGE }

interface ProfileRepository {
    fun findByUsername(username: String, viewerId: UUID): UserProfile?
    fun findIdByUsername(username: String): UUID?
    fun findUsernameById(userId: UUID): String?

    /** Gönderilmeyen alanlar değişmez. */
    fun updateProfile(userId: UUID, fullName: String?, bio: String?, clearBio: Boolean, now: Instant)

    fun checkAvatarMedia(mediaId: UUID, ownerId: UUID): AvatarMediaCheck
    fun setAvatar(userId: UUID, mediaId: UUID?, now: Instant)

    /** @return Takip edilen kullanıcının güncel takipçi sayısı. */
    fun setFollow(followerId: UUID, followeeId: UUID, active: Boolean, now: Instant): Long

    fun followers(userId: UUID, viewerId: UUID, cursor: FollowCursor?, limit: Int): List<FollowEdge>
    fun following(userId: UUID, viewerId: UUID, cursor: FollowCursor?, limit: Int): List<FollowEdge>
    fun followerCount(userId: UUID): Long
    fun followingCount(userId: UUID): Long
}

class JdbcProfileRepository(private val dataSource: DataSource) : ProfileRepository {

    override fun findByUsername(username: String, viewerId: UUID): UserProfile? =
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """SELECT u.id, u.full_name, u.username, u.bio, u.created_at,
                          am.storage_key AS avatar_key,
                          (SELECT COUNT(*) FROM posts p WHERE p.owner_id = u.id AND p.status = 'PUBLISHED') AS post_count,
                          (SELECT COUNT(*) FROM follows f WHERE f.followee_id = u.id) AS follower_count,
                          (SELECT COUNT(*) FROM follows f WHERE f.follower_id = u.id) AS following_count,
                          EXISTS(SELECT 1 FROM follows f WHERE f.follower_id = ? AND f.followee_id = u.id) AS followed_by_viewer
                   FROM users u
                   LEFT JOIN media_assets am ON am.id = u.avatar_media_id AND am.status = 'READY'
                   WHERE u.username_normalized = ?"""
            ).use { statement ->
                statement.setObject(1, viewerId)
                statement.setString(2, username.lowercase())
                statement.executeQuery().use { results ->
                    if (!results.next()) return@use null
                    val id = results.getObject("id", UUID::class.java)
                    UserProfile(
                        id = id,
                        fullName = results.getString("full_name"),
                        username = results.getString("username"),
                        bio = results.getString("bio"),
                        avatarStorageKey = results.getString("avatar_key"),
                        createdAt = results.getTimestamp("created_at").toInstant(),
                        postCount = results.getLong("post_count"),
                        followerCount = results.getLong("follower_count"),
                        followingCount = results.getLong("following_count"),
                        followedByViewer = results.getBoolean("followed_by_viewer"),
                        isViewer = id == viewerId,
                    )
                }
            }
        }

    override fun findIdByUsername(username: String): UUID? = dataSource.connection.use { connection ->
        connection.prepareStatement("SELECT id FROM users WHERE username_normalized = ?").use { statement ->
            statement.setString(1, username.lowercase())
            statement.executeQuery().use { results ->
                if (results.next()) results.getObject("id", UUID::class.java) else null
            }
        }
    }

    override fun findUsernameById(userId: UUID): String? = dataSource.connection.use { connection ->
        connection.prepareStatement("SELECT username FROM users WHERE id = ?").use { statement ->
            statement.setObject(1, userId)
            statement.executeQuery().use { results -> if (results.next()) results.getString("username") else null }
        }
    }

    override fun updateProfile(userId: UUID, fullName: String?, bio: String?, clearBio: Boolean, now: Instant) {
        // COALESCE ile: parametre null geldiyse sütun olduğu gibi kalır.
        // Biyografiyi silmek ayrı bir bayrakla isteniyor, yoksa "null gönder"
        // ile "değiştirme" ayırt edilemezdi.
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """UPDATE users
                   SET full_name = COALESCE(?, full_name),
                       bio = CASE WHEN ? THEN NULL ELSE COALESCE(?, bio) END,
                       updated_at = ?
                   WHERE id = ?"""
            ).use { statement ->
                statement.setString(1, fullName)
                statement.setBoolean(2, clearBio)
                statement.setString(3, bio)
                statement.setTimestamp(4, Timestamp.from(now))
                statement.setObject(5, userId)
                statement.executeUpdate()
            }
        }
    }

    override fun checkAvatarMedia(mediaId: UUID, ownerId: UUID): AvatarMediaCheck =
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                "SELECT owner_id, status, mime_type FROM media_assets WHERE id = ?"
            ).use { statement ->
                statement.setObject(1, mediaId)
                statement.executeQuery().use { results ->
                    if (!results.next()) return@use AvatarMediaCheck.NOT_FOUND
                    when {
                        results.getObject("owner_id", UUID::class.java) != ownerId -> AvatarMediaCheck.NOT_OWNED
                        results.getString("status") != "READY" -> AvatarMediaCheck.NOT_READY
                        // Avatar bir video olamaz; medya yükleme mp4 kabul ediyor.
                        !results.getString("mime_type").startsWith("image/") -> AvatarMediaCheck.NOT_AN_IMAGE
                        else -> AvatarMediaCheck.OK
                    }
                }
            }
        }

    override fun setAvatar(userId: UUID, mediaId: UUID?, now: Instant) {
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                "UPDATE users SET avatar_media_id = ?, updated_at = ? WHERE id = ?"
            ).use { statement ->
                statement.setObject(1, mediaId)
                statement.setTimestamp(2, Timestamp.from(now))
                statement.setObject(3, userId)
                statement.executeUpdate()
            }
        }
    }

    override fun setFollow(followerId: UUID, followeeId: UUID, active: Boolean, now: Instant): Long =
        dataSource.connection.use { connection ->
            if (active) {
                // Beğeni gibi idempotent: aynı isteği iki kez göndermek hata değil.
                connection.prepareStatement(
                    "INSERT INTO follows (follower_id, followee_id, created_at) VALUES (?, ?, ?) ON CONFLICT DO NOTHING"
                ).use { statement ->
                    statement.setObject(1, followerId)
                    statement.setObject(2, followeeId)
                    statement.setTimestamp(3, Timestamp.from(now))
                    statement.executeUpdate()
                }
            } else {
                connection.prepareStatement(
                    "DELETE FROM follows WHERE follower_id = ? AND followee_id = ?"
                ).use { statement ->
                    statement.setObject(1, followerId)
                    statement.setObject(2, followeeId)
                    statement.executeUpdate()
                }
            }
            connection.countFollows("followee_id", followeeId)
        }

    override fun followers(userId: UUID, viewerId: UUID, cursor: FollowCursor?, limit: Int): List<FollowEdge> =
        edges(
            joinColumn = "f.follower_id",
            filterColumn = "f.followee_id",
            userId = userId,
            viewerId = viewerId,
            cursor = cursor,
            limit = limit,
        )

    override fun following(userId: UUID, viewerId: UUID, cursor: FollowCursor?, limit: Int): List<FollowEdge> =
        edges(
            joinColumn = "f.followee_id",
            filterColumn = "f.follower_id",
            userId = userId,
            viewerId = viewerId,
            cursor = cursor,
            limit = limit,
        )

    override fun followerCount(userId: UUID): Long =
        dataSource.connection.use { it.countFollows("followee_id", userId) }

    override fun followingCount(userId: UUID): Long =
        dataSource.connection.use { it.countFollows("follower_id", userId) }

    /**
     * Takipçi ve takip listeleri aynı sorgu; yalnızca hangi sütunun süzüldüğü ve
     * hangisinin karşı tarafı gösterdiği değişiyor. Sütun adları kod içinde sabit,
     * dışarıdan gelmiyor.
     */
    private fun edges(
        joinColumn: String,
        filterColumn: String,
        userId: UUID,
        viewerId: UUID,
        cursor: FollowCursor?,
        limit: Int,
    ): List<FollowEdge> = dataSource.connection.use { connection ->
        val cursorClause = if (cursor == null) "" else "AND (f.created_at, u.id) < (?, ?)"
        connection.prepareStatement(
            """SELECT u.id, u.full_name, u.username, f.created_at,
                      am.storage_key AS avatar_key,
                      EXISTS(SELECT 1 FROM follows v WHERE v.follower_id = ? AND v.followee_id = u.id) AS followed_by_viewer
               FROM follows f
               JOIN users u ON u.id = $joinColumn
               LEFT JOIN media_assets am ON am.id = u.avatar_media_id AND am.status = 'READY'
               WHERE $filterColumn = ? $cursorClause
               ORDER BY f.created_at DESC, u.id DESC LIMIT ?"""
        ).use { statement ->
            var index = 1
            statement.setObject(index++, viewerId)
            statement.setObject(index++, userId)
            if (cursor != null) {
                statement.setTimestamp(index++, Timestamp.from(cursor.createdAt))
                statement.setObject(index++, cursor.userId)
            }
            statement.setInt(index, limit)
            statement.executeQuery().use { results ->
                buildList { while (results.next()) add(results.toEdge()) }
            }
        }
    }

    private fun java.sql.Connection.countFollows(column: String, userId: UUID): Long =
        prepareStatement("SELECT COUNT(*) FROM follows WHERE $column = ?").use { statement ->
            statement.setObject(1, userId)
            statement.executeQuery().use { results -> results.next(); results.getLong(1) }
        }

    private fun ResultSet.toEdge() = FollowEdge(
        userId = getObject("id", UUID::class.java),
        fullName = getString("full_name"),
        username = getString("username"),
        avatarStorageKey = getString("avatar_key"),
        followedByViewer = getBoolean("followed_by_viewer"),
        createdAt = getTimestamp("created_at").toInstant(),
    )
}
