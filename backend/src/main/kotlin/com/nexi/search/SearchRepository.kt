package com.nexi.search

import com.nexi.moderation.BlockFilter
import com.nexi.topics.Topic
import com.nexi.topics.toTopic
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

interface SearchRepository {
    fun users(viewerId: UUID, query: String, cursor: RankedCursor?, limit: Int): List<SearchUser>
    fun topics(query: String, limit: Int): List<Topic>
}

class JdbcSearchRepository(private val dataSource: DataSource) : SearchRepository {

    override fun users(viewerId: UUID, query: String, cursor: RankedCursor?, limit: Int): List<SearchUser> =
        dataSource.connection.use { connection ->
            val cursorClause = if (cursor == null) "" else "WHERE (rank, created_at, id) < (?, ?, ?)"
            // İki yoldan eşleşme: tam kelime (tsvector) ve kullanıcı adında
            // parça (trigram). Parçalı arama "giz" yazınca "gizem"i bulsun diye;
            // yalnızca tam kelimeye bakmak yazarken arama deneyimini bozardı.
            connection.prepareStatement(
                """SELECT * FROM (
                     SELECT u.id, u.full_name, u.username, u.created_at,
                            am.storage_key AS avatar_key,
                            (SELECT COUNT(*) FROM follows f WHERE f.followee_id = u.id) AS follower_count,
                            EXISTS(SELECT 1 FROM follows f WHERE f.follower_id = ? AND f.followee_id = u.id) AS followed,
                            GREATEST(
                              ts_rank(u.search_vector, websearch_to_tsquery('turkish_simple', ?)),
                              similarity(u.username_normalized, ?)
                            ) AS rank
                     FROM users u
                     LEFT JOIN media_assets am ON am.id = u.avatar_media_id AND am.status = 'READY'
                     WHERE (u.search_vector @@ websearch_to_tsquery('turkish_simple', ?)
                            OR u.username_normalized LIKE ? ESCAPE E'\\')
                       AND ${BlockFilter.notBlocked("u.id")}
                   ) ranked
                   $cursorClause
                   ORDER BY rank DESC, created_at DESC, id DESC LIMIT ?"""
            ).use { statement ->
                val normalized = query.lowercase()
                var index = 1
                statement.setObject(index++, viewerId)
                statement.setString(index++, query)
                statement.setString(index++, normalized)
                statement.setString(index++, query)
                statement.setString(index++, normalized.escapeLikePrefix())
                repeat(BlockFilter.BINDINGS) { statement.setObject(index++, viewerId) }
                if (cursor != null) {
                    statement.setDouble(index++, cursor.rank)
                    statement.setTimestamp(index++, Timestamp.from(cursor.createdAt))
                    statement.setObject(index++, cursor.id)
                }
                statement.setInt(index, limit)
                statement.executeQuery().use { results ->
                    buildList {
                        while (results.next()) {
                            val id = results.getObject("id", UUID::class.java)
                            add(
                                SearchUser(
                                    id = id,
                                    fullName = results.getString("full_name"),
                                    username = results.getString("username"),
                                    avatarStorageKey = results.getString("avatar_key"),
                                    followerCount = results.getLong("follower_count"),
                                    followedByViewer = results.getBoolean("followed"),
                                    isViewer = id == viewerId,
                                    rank = results.getDouble("rank"),
                                    createdAt = results.getTimestamp("created_at").toInstant(),
                                )
                            )
                        }
                    }
                }
            }
        }

    override fun topics(query: String, limit: Int): List<Topic> = dataSource.connection.use { connection ->
        connection.prepareStatement(
            """SELECT t.id, t.slug, t.name, t.description, t.icon, t.color_hex, t.display_order
               FROM topics t
               WHERE t.active
                 AND to_tsvector('turkish_simple', t.name || ' ' || coalesce(t.description, ''))
                     @@ websearch_to_tsquery('turkish_simple', ?)
               ORDER BY t.display_order LIMIT ?"""
        ).use { statement ->
            statement.setString(1, query)
            statement.setInt(2, limit)
            statement.executeQuery().use { results ->
                buildList { while (results.next()) add(results.toTopic()) }
            }
        }
    }
}

/** `%` ve `_` kullanıcı girdisinde SQL jokeri değil, düz karakterdir. */
internal fun String.escapeLikePrefix(): String =
    replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"

/** Ortak alan; imleç kodlaması için. */
internal fun SearchUser.cursor() = RankedCursor(rank, createdAt, id)

internal fun rankedCursorOf(rank: Double, createdAt: Instant, id: UUID) = RankedCursor(rank, createdAt, id)
