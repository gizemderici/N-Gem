package com.nexi.recommendations

import java.sql.Timestamp
import java.util.UUID
import javax.sql.DataSource

interface RecommendationRepository {
    val personalizationAvailable: Boolean
    fun append(events: List<RecommendationEvent>): Int
    fun recentSignals(userId: UUID, limit: Int): List<RecommendationSignal>
    fun profileStats(userId: UUID): RecommendationProfileStats
    fun clear(userId: UUID)
}

object EmptyRecommendationRepository : RecommendationRepository {
    override val personalizationAvailable = false
    override fun append(events: List<RecommendationEvent>) = 0
    override fun recentSignals(userId: UUID, limit: Int) = emptyList<RecommendationSignal>()
    override fun profileStats(userId: UUID) = RecommendationProfileStats(0, null)
    override fun clear(userId: UUID) = Unit
}

class JdbcRecommendationRepository(private val dataSource: DataSource) : RecommendationRepository {
    override val personalizationAvailable = true

    override fun append(events: List<RecommendationEvent>): Int {
        if (events.isEmpty()) return 0
        return dataSource.connection.use { connection ->
            connection.prepareStatement(
                """INSERT INTO recommendation_events
                   (id, user_id, post_id, client_event_id, session_id, feed_request_id, event_type,
                    surface, position, dwell_millis, completion_ratio, local_hour,
                    timezone_offset_minutes, target_feature, occurred_at, received_at)
                   VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                   ON CONFLICT (user_id, client_event_id) DO NOTHING"""
            ).use { statement ->
                events.forEach { event ->
                    statement.setObject(1, event.id)
                    statement.setObject(2, event.userId)
                    statement.setObject(3, event.postId)
                    statement.setObject(4, event.clientEventId)
                    statement.setObject(5, event.sessionId)
                    statement.setObject(6, event.feedRequestId)
                    statement.setString(7, event.eventType.name)
                    statement.setString(8, event.surface)
                    statement.setNullableInt(9, event.position)
                    statement.setNullableLong(10, event.dwellMillis)
                    statement.setNullableDouble(11, event.completionRatio)
                    statement.setInt(12, event.localHour)
                    statement.setInt(13, event.timezoneOffsetMinutes)
                    statement.setString(14, event.targetFeature)
                    statement.setTimestamp(15, Timestamp.from(event.occurredAt))
                    statement.setTimestamp(16, Timestamp.from(event.receivedAt))
                    statement.addBatch()
                }
                statement.executeBatch().sumOf { if (it > 0) it else 0 }
            }
        }
    }

    override fun recentSignals(userId: UUID, limit: Int): List<RecommendationSignal> =
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """SELECT re.event_type, re.post_id, re.dwell_millis, re.completion_ratio,
                          re.local_hour, re.target_feature, re.occurred_at,
                          p.owner_id, p.body,
                          COALESCE((
                              SELECT array_agg(t.slug ORDER BY t.display_order)
                              FROM post_topics pt JOIN topics t ON t.id = pt.topic_id
                              WHERE pt.post_id = p.id
                          ), '{}') AS topic_slugs,
                          CASE
                            WHEN EXISTS (
                                SELECT 1 FROM post_media pm JOIN media_assets m ON m.id = pm.media_id
                                WHERE pm.post_id = p.id AND m.mime_type LIKE 'video/%'
                            ) THEN 'video'
                            WHEN EXISTS (SELECT 1 FROM post_media pm WHERE pm.post_id = p.id) THEN 'image'
                            ELSE 'text'
                          END AS media_type
                   FROM recommendation_events re
                   LEFT JOIN posts p ON p.id = re.post_id
                   WHERE re.user_id = ?
                   ORDER BY re.occurred_at DESC
                   LIMIT ?"""
            ).use { statement ->
                statement.setObject(1, userId)
                statement.setInt(2, limit.coerceIn(1, 5_000))
                statement.executeQuery().use { results ->
                    buildList {
                        while (results.next()) {
                            add(
                                RecommendationSignal(
                                    eventType = RecommendationEventType.valueOf(results.getString("event_type")),
                                    postId = results.getObject("post_id", UUID::class.java),
                                    authorId = results.getObject("owner_id", UUID::class.java),
                                    body = results.getString("body"),
                                    topicSlugs = results.readTopicSlugs(),
                                    mediaType = results.getString("media_type"),
                                    dwellMillis = results.getLong("dwell_millis").let { if (results.wasNull()) null else it },
                                    completionRatio = results.getDouble("completion_ratio").let { if (results.wasNull()) null else it },
                                    localHour = results.getInt("local_hour"),
                                    targetFeature = results.getString("target_feature"),
                                    occurredAt = results.getTimestamp("occurred_at").toInstant(),
                                )
                            )
                        }
                    }
                }
            }
        }

    override fun profileStats(userId: UUID): RecommendationProfileStats = dataSource.connection.use { connection ->
        connection.prepareStatement(
            "SELECT COUNT(*) AS event_count, MAX(occurred_at) AS last_event FROM recommendation_events WHERE user_id = ?"
        ).use { statement ->
            statement.setObject(1, userId)
            statement.executeQuery().use { results ->
                results.next()
                RecommendationProfileStats(
                    eventCount = results.getLong("event_count"),
                    lastUpdatedAt = results.getTimestamp("last_event")?.toInstant(),
                )
            }
        }
    }

    override fun clear(userId: UUID) {
        dataSource.connection.use { connection ->
            connection.prepareStatement("DELETE FROM recommendation_events WHERE user_id = ?").use { statement ->
                statement.setObject(1, userId)
                statement.executeUpdate()
            }
        }
    }
}

/**
 * Konusuz gönderilerde `LEFT JOIN` yüzünden dizi `NULL` gelebilir; sorgudaki
 * `COALESCE` boş diziye çeviriyor, yine de silinmiş gönderiye bağlı olaylarda
 * kolonun tamamı `NULL` olur.
 */
private fun java.sql.ResultSet.readTopicSlugs(): List<String> {
    val array = getArray("topic_slugs") ?: return emptyList()
    return try {
        (array.array as? Array<*>)?.filterIsInstance<String>().orEmpty()
    } finally {
        array.free()
    }
}

private fun java.sql.PreparedStatement.setNullableInt(index: Int, value: Int?) {
    if (value == null) setNull(index, java.sql.Types.INTEGER) else setInt(index, value)
}

private fun java.sql.PreparedStatement.setNullableLong(index: Int, value: Long?) {
    if (value == null) setNull(index, java.sql.Types.BIGINT) else setLong(index, value)
}

private fun java.sql.PreparedStatement.setNullableDouble(index: Int, value: Double?) {
    if (value == null) setNull(index, java.sql.Types.DOUBLE) else setDouble(index, value)
}
