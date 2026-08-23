package com.nexi.stories

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

/** Hikâyeye eklenmek istenen medyanın kontrol sonucu. */
enum class StoryMediaCheck { OK, NOT_FOUND, NOT_OWNED, NOT_READY, ALREADY_USED }

interface StoryRepository {
    fun checkMedia(mediaId: UUID, ownerId: UUID): StoryMediaCheck
    fun create(story: Story): StoryDetails?

    /** Takip edilenlerin ve kullanıcının kendi hikâyeleri; süresi dolmuşlar hariç. */
    fun feed(viewerId: UUID, now: Instant, limit: Int): List<StoryDetails>

    fun byOwner(username: String, viewerId: UUID, now: Instant): List<StoryDetails>?

    fun findDetails(storyId: UUID, viewerId: UUID, now: Instant): StoryDetails?

    /** @return Kayıt eklendiyse `true`; zaten görülmüşse `false`. */
    fun markViewed(storyId: UUID, viewerId: UUID, now: Instant): Boolean

    fun viewers(storyId: UUID, cursor: StoryViewCursor?, limit: Int): List<StoryViewer>
    fun viewerCount(storyId: UUID): Long

    /** @return Serbest kalan depolama anahtarı; hikâye bulunamazsa `null`. */
    fun markDeleted(storyId: UUID, ownerId: UUID, now: Instant): String?

    /** Süresi dolmuş hikâyeleri toplar; medyaları serbest bırakılacak. */
    fun expireOlderThan(now: Instant, limit: Int): List<Pair<UUID, String>>
}

class JdbcStoryRepository(private val dataSource: DataSource) : StoryRepository {

    override fun checkMedia(mediaId: UUID, ownerId: UUID): StoryMediaCheck =
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """SELECT m.owner_id, m.status, m.processing_status,
                          EXISTS(SELECT 1 FROM post_media pm WHERE pm.media_id = m.id) AS in_post,
                          EXISTS(SELECT 1 FROM stories s WHERE s.media_id = m.id) AS in_story
                   FROM media_assets m WHERE m.id = ?"""
            ).use { statement ->
                statement.setObject(1, mediaId)
                statement.executeQuery().use { results ->
                    if (!results.next()) return@use StoryMediaCheck.NOT_FOUND
                    when {
                        results.getObject("owner_id", UUID::class.java) != ownerId -> StoryMediaCheck.NOT_OWNED
                        results.getString("status") != MediaStatus.READY.name -> StoryMediaCheck.NOT_READY
                        results.getString("processing_status") != MediaProcessingStatus.READY.name ->
                            StoryMediaCheck.NOT_READY
                        results.getBoolean("in_post") || results.getBoolean("in_story") ->
                            StoryMediaCheck.ALREADY_USED
                        else -> StoryMediaCheck.OK
                    }
                }
            }
        }

    override fun create(story: Story): StoryDetails? = dataSource.connection.use { connection ->
        connection.prepareStatement(
            """INSERT INTO stories
               (id, owner_id, media_id, caption, status, published_at, expires_at, created_at, updated_at)
               VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)"""
        ).use { statement ->
            statement.setObject(1, story.id)
            statement.setObject(2, story.ownerId)
            statement.setObject(3, story.mediaId)
            statement.setString(4, story.caption)
            statement.setString(5, story.status.name)
            statement.setTimestamp(6, Timestamp.from(story.publishedAt))
            statement.setTimestamp(7, Timestamp.from(story.expiresAt))
            statement.setTimestamp(8, Timestamp.from(story.publishedAt))
            statement.setTimestamp(9, Timestamp.from(story.publishedAt))
            statement.executeUpdate()
        }
        findDetails(story.id, story.ownerId, story.publishedAt)
    }

    override fun feed(viewerId: UUID, now: Instant, limit: Int): List<StoryDetails> =
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """${detailsSelect()}
                   WHERE s.status = 'PUBLISHED' AND s.expires_at > ?
                     AND (s.owner_id = ? OR EXISTS (
                           SELECT 1 FROM follows f WHERE f.follower_id = ? AND f.followee_id = s.owner_id))
                     AND ${BlockFilter.notBlocked("s.owner_id")}
                   ORDER BY s.owner_id, s.published_at LIMIT ?"""
            ).use { statement ->
                var index = 1
                repeat(VIEWER_BINDINGS) { statement.setObject(index++, viewerId) }
                statement.setTimestamp(index++, Timestamp.from(now))
                statement.setObject(index++, viewerId)
                statement.setObject(index++, viewerId)
                repeat(BlockFilter.BINDINGS) { statement.setObject(index++, viewerId) }
                statement.setInt(index, limit)
                statement.executeQuery().use { results ->
                    buildList { while (results.next()) add(results.toDetails()) }
                }
            }
        }

    /** Kullanıcı yoksa ya da engelliyse `null`; hikâyesi yoksa boş liste. */
    override fun byOwner(username: String, viewerId: UUID, now: Instant): List<StoryDetails>? =
        dataSource.connection.use { connection ->
            val ownerId = connection.prepareStatement(
                "SELECT u.id FROM users u WHERE u.username_normalized = ? AND ${BlockFilter.notBlocked("u.id")}"
            ).use { statement ->
                statement.setString(1, username.lowercase())
                statement.setObject(2, viewerId)
                statement.setObject(3, viewerId)
                statement.executeQuery().use { results ->
                    if (results.next()) results.getObject("id", UUID::class.java) else null
                }
            } ?: return@use null

            connection.prepareStatement(
                """${detailsSelect()}
                   WHERE s.status = 'PUBLISHED' AND s.expires_at > ? AND s.owner_id = ?
                   ORDER BY s.published_at"""
            ).use { statement ->
                var index = 1
                repeat(VIEWER_BINDINGS) { statement.setObject(index++, viewerId) }
                statement.setTimestamp(index++, Timestamp.from(now))
                statement.setObject(index, ownerId)
                statement.executeQuery().use { results ->
                    buildList { while (results.next()) add(results.toDetails()) }
                }
            }
        }

    override fun findDetails(storyId: UUID, viewerId: UUID, now: Instant): StoryDetails? =
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """${detailsSelect()}
                   WHERE s.id = ? AND s.status = 'PUBLISHED' AND s.expires_at > ?
                     AND ${BlockFilter.notBlocked("s.owner_id")}"""
            ).use { statement ->
                var index = 1
                repeat(VIEWER_BINDINGS) { statement.setObject(index++, viewerId) }
                statement.setObject(index++, storyId)
                statement.setTimestamp(index++, Timestamp.from(now))
                repeat(BlockFilter.BINDINGS) { statement.setObject(index++, viewerId) }
                statement.executeQuery().use { results ->
                    if (results.next()) results.toDetails() else null
                }
            }
        }

    override fun markViewed(storyId: UUID, viewerId: UUID, now: Instant): Boolean =
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                "INSERT INTO story_views (story_id, viewer_id, viewed_at) VALUES (?, ?, ?) ON CONFLICT DO NOTHING"
            ).use { statement ->
                statement.setObject(1, storyId)
                statement.setObject(2, viewerId)
                statement.setTimestamp(3, Timestamp.from(now))
                statement.executeUpdate() == 1
            }
        }

    override fun viewers(storyId: UUID, cursor: StoryViewCursor?, limit: Int): List<StoryViewer> =
        dataSource.connection.use { connection ->
            val cursorClause = if (cursor == null) "" else "AND (sv.viewed_at, u.id) < (?, ?)"
            connection.prepareStatement(
                """SELECT u.id, u.full_name, u.username, sv.viewed_at, am.storage_key AS avatar_key
                   FROM story_views sv
                   JOIN users u ON u.id = sv.viewer_id
                   LEFT JOIN media_assets am ON am.id = u.avatar_media_id AND am.status = 'READY'
                   WHERE sv.story_id = ? $cursorClause
                   ORDER BY sv.viewed_at DESC, u.id DESC LIMIT ?"""
            ).use { statement ->
                var index = 1
                statement.setObject(index++, storyId)
                if (cursor != null) {
                    statement.setTimestamp(index++, Timestamp.from(cursor.viewedAt))
                    statement.setObject(index++, cursor.viewerId)
                }
                statement.setInt(index, limit)
                statement.executeQuery().use { results ->
                    buildList {
                        while (results.next()) {
                            add(
                                StoryViewer(
                                    userId = results.getObject("id", UUID::class.java),
                                    fullName = results.getString("full_name"),
                                    username = results.getString("username"),
                                    avatarStorageKey = results.getString("avatar_key"),
                                    viewedAt = results.getTimestamp("viewed_at").toInstant(),
                                )
                            )
                        }
                    }
                }
            }
        }

    override fun viewerCount(storyId: UUID): Long = dataSource.connection.use { connection ->
        connection.prepareStatement("SELECT COUNT(*) FROM story_views WHERE story_id = ?").use { statement ->
            statement.setObject(1, storyId)
            statement.executeQuery().use { results -> results.next(); results.getLong(1) }
        }
    }

    override fun markDeleted(storyId: UUID, ownerId: UUID, now: Instant): String? =
        dataSource.connection.use { connection ->
            connection.autoCommit = false
            try {
                val key = connection.prepareStatement(
                    """SELECT m.storage_key FROM stories s
                       JOIN media_assets m ON m.id = s.media_id
                       WHERE s.id = ? AND s.owner_id = ? AND s.status = 'PUBLISHED'"""
                ).use { statement ->
                    statement.setObject(1, storyId)
                    statement.setObject(2, ownerId)
                    statement.executeQuery().use { results ->
                        if (results.next()) results.getString("storage_key") else null
                    }
                }
                if (key == null) {
                    connection.rollback()
                    return@use null
                }

                releaseStory(connection, storyId, now)
                connection.commit()
                key
            } catch (exception: Throwable) {
                connection.rollback()
                throw exception
            } finally {
                connection.autoCommit = true
            }
        }

    override fun expireOlderThan(now: Instant, limit: Int): List<Pair<UUID, String>> =
        dataSource.connection.use { connection ->
            connection.autoCommit = false
            try {
                val expired = connection.prepareStatement(
                    """SELECT s.id, m.storage_key FROM stories s
                       JOIN media_assets m ON m.id = s.media_id
                       WHERE s.status = 'PUBLISHED' AND s.expires_at <= ?
                       ORDER BY s.expires_at LIMIT ?"""
                ).use { statement ->
                    statement.setTimestamp(1, Timestamp.from(now))
                    statement.setInt(2, limit)
                    statement.executeQuery().use { results ->
                        buildList {
                            while (results.next()) {
                                add(results.getObject("id", UUID::class.java) to results.getString("storage_key"))
                            }
                        }
                    }
                }
                expired.forEach { (id, _) -> releaseStory(connection, id, now) }
                connection.commit()
                expired
            } catch (exception: Throwable) {
                connection.rollback()
                throw exception
            } finally {
                connection.autoCommit = true
            }
        }

    /**
     * Hikâyeyi silinmiş işaretler ve medyasını serbest bırakır.
     *
     * `stories.media_id` üzerindeki UNIQUE kısıtı yüzünden bağ kalırsa o medya
     * bir daha kullanılamazdı; gönderi silmedeki davranışın aynısı.
     */
    private fun releaseStory(connection: java.sql.Connection, storyId: UUID, now: Instant) {
        connection.prepareStatement(
            """UPDATE media_assets SET status = 'DELETED', updated_at = ?
               WHERE id = (SELECT media_id FROM stories WHERE id = ?)"""
        ).use { statement ->
            statement.setTimestamp(1, Timestamp.from(now))
            statement.setObject(2, storyId)
            statement.executeUpdate()
        }
        connection.prepareStatement(
            "UPDATE stories SET status = 'DELETED', updated_at = ? WHERE id = ?"
        ).use { statement ->
            statement.setTimestamp(1, Timestamp.from(now))
            statement.setObject(2, storyId)
            statement.executeUpdate()
        }
    }

    private fun detailsSelect() =
        """SELECT s.id, s.owner_id, s.media_id, s.caption, s.status, s.published_at, s.expires_at,
                  u.full_name, u.username,
                  au.storage_key AS author_avatar_key,
                  EXISTS(SELECT 1 FROM follows f WHERE f.follower_id = ? AND f.followee_id = s.owner_id) AS author_followed,
                  EXISTS(SELECT 1 FROM story_views sv WHERE sv.story_id = s.id AND sv.viewer_id = ?) AS seen_by_viewer,
                  (SELECT COUNT(*) FROM story_views sv WHERE sv.story_id = s.id) AS view_count,
                  m.id AS media_id_col, m.owner_id AS media_owner, m.storage_key, m.original_filename,
                  m.mime_type, m.declared_size_bytes, m.actual_size_bytes, m.status AS media_status,
                  m.created_at AS media_created, m.updated_at AS media_updated,
                  m.processing_status, m.failure_reason, m.duration_seconds, m.width, m.height,
                  tm.storage_key AS thumbnail_key
           FROM stories s
           JOIN users u ON u.id = s.owner_id
           JOIN media_assets m ON m.id = s.media_id
           LEFT JOIN media_assets au ON au.id = u.avatar_media_id AND au.status = 'READY'
           LEFT JOIN media_assets tm ON tm.id = m.thumbnail_media_id AND tm.status = 'READY'"""

    private fun ResultSet.toDetails(): StoryDetails {
        val ownerId = getObject("owner_id", UUID::class.java)
        return StoryDetails(
            story = Story(
                id = getObject("id", UUID::class.java),
                ownerId = ownerId,
                mediaId = getObject("media_id", UUID::class.java),
                caption = getString("caption"),
                status = StoryStatus.valueOf(getString("status")),
                publishedAt = getTimestamp("published_at").toInstant(),
                expiresAt = getTimestamp("expires_at").toInstant(),
            ),
            author = PostAuthorResponse(
                id = ownerId.toString(),
                fullName = getString("full_name"),
                username = getString("username"),
                followedByMe = getBoolean("author_followed"),
                avatarStorageKey = getString("author_avatar_key"),
            ),
            media = MediaAsset(
                id = getObject("media_id_col", UUID::class.java),
                ownerId = getObject("media_owner", UUID::class.java),
                storageKey = getString("storage_key"),
                originalFilename = getString("original_filename"),
                mimeType = getString("mime_type"),
                declaredSizeBytes = getLong("declared_size_bytes"),
                actualSizeBytes = getLong("actual_size_bytes").let { if (wasNull()) null else it },
                status = MediaStatus.valueOf(getString("media_status")),
                createdAt = getTimestamp("media_created").toInstant(),
                updatedAt = getTimestamp("media_updated").toInstant(),
                processingStatus = MediaProcessingStatus.valueOf(getString("processing_status")),
                failureReason = getString("failure_reason"),
                durationSeconds = getDouble("duration_seconds").let { if (wasNull()) null else it },
                width = getInt("width").let { if (wasNull()) null else it },
                height = getInt("height").let { if (wasNull()) null else it },
            ),
            thumbnailStorageKey = getString("thumbnail_key"),
            viewCount = getLong("view_count"),
            seenByViewer = getBoolean("seen_by_viewer"),
        )
    }

    private companion object {
        /** `detailsSelect` içinde viewerId iki kez bağlanıyor: takip ve görülme kontrolü. */
        const val VIEWER_BINDINGS = 2
    }
}
