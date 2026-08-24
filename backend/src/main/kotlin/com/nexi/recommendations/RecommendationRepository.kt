package com.nexi.recommendations

import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

interface RecommendationRepository {
    val personalizationAvailable: Boolean
    fun append(events: List<RecommendationEvent>): Int

    /**
     * `notAfter` sıralamayı tekrar üretilebilir kılar: ikinci sayfa
     * hesaplanırken araya giren yeni olaylar sıralamayı kaydırırsa aynı
     * gönderi iki sayfada birden çıkardı.
     */
    fun recentSignals(userId: UUID, limit: Int, notAfter: Instant? = null): List<RecommendationSignal>
    fun profileStats(userId: UUID): RecommendationProfileStats

    /**
     * Kullanıcının öğrenilmiş profilini **tamamen** siler: olaylar, profil
     * anlık görüntüleri ve akış soy kütüğü.
     *
     * Yalnızca olayları silmek yetmiyordu; kullanıcı profilini sıfırladığında
     * `feed_requests` ve `user_feature_snapshots` yerinde kalıyor, yani
     * "sildim" dediği veri hâlâ eğitim setine giriyordu.
     *
     * Gizlenen gönderiler (`hidden_posts`) kasıtlı olarak silinmiyor: o bir
     * öğrenilmiş profil değil, kullanıcının açık tercihi. Sıfırlamayla
     * silinseydi gizlediği içerik akışa geri dönerdi.
     */
    fun clear(userId: UUID)

    /** Kullanıcının indirebileceği öneri verisinin tamamı. */
    fun export(userId: UUID): RecommendationExport

    /**
     * Saklama süresi geçmiş olayları ve soy kütüğünü siler; silinen satır
     * sayısını döndürür. Toplu iş süpürme döngüsünden çağrılıyor.
     */
    fun deleteOlderThan(cutoff: Instant, batchSize: Int): Int

    /** Kullanıcının gizlediği gönderi; bir daha aday havuzuna girmez. */
    fun hide(userId: UUID, postId: UUID, now: Instant)
}

object EmptyRecommendationRepository : RecommendationRepository {
    override val personalizationAvailable = false
    override fun append(events: List<RecommendationEvent>) = 0
    override fun recentSignals(userId: UUID, limit: Int, notAfter: Instant?) = emptyList<RecommendationSignal>()
    override fun profileStats(userId: UUID) = RecommendationProfileStats(0, null)
    override fun clear(userId: UUID) = Unit
    override fun export(userId: UUID) = RecommendationExport()
    override fun deleteOlderThan(cutoff: Instant, batchSize: Int) = 0
    override fun hide(userId: UUID, postId: UUID, now: Instant) = Unit
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
                    timezone_offset_minutes, target_feature, occurred_at, received_at,
                    schema_version, app_version, platform, candidate_source)
                   VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
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
                    statement.setInt(17, event.schemaVersion)
                    statement.setString(18, event.appVersion)
                    statement.setString(19, event.platform?.wireName)
                    statement.setString(20, event.candidateSource?.name)
                    statement.addBatch()
                }
                statement.executeBatch().sumOf { if (it > 0) it else 0 }
            }
        }
    }

    override fun hide(userId: UUID, postId: UUID, now: Instant) {
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """INSERT INTO hidden_posts (user_id, post_id, created_at) VALUES (?, ?, ?)
                   ON CONFLICT (user_id, post_id) DO NOTHING"""
            ).use { statement ->
                statement.setObject(1, userId)
                statement.setObject(2, postId)
                statement.setTimestamp(3, Timestamp.from(now))
                statement.executeUpdate()
            }
        }
    }

    override fun recentSignals(userId: UUID, limit: Int, notAfter: Instant?): List<RecommendationSignal> =
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
                   WHERE re.user_id = ? AND (CAST(? AS TIMESTAMPTZ) IS NULL OR re.occurred_at <= ?)
                   ORDER BY re.occurred_at DESC
                   LIMIT ?"""
            ).use { statement ->
                val cutoff = notAfter?.let(Timestamp::from)
                statement.setObject(1, userId)
                statement.setTimestamp(2, cutoff)
                statement.setTimestamp(3, cutoff)
                statement.setInt(4, limit.coerceIn(1, 5_000))
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
            connection.autoCommit = false
            try {
                // `feed_candidates` ve `user_feature_snapshots`,
                // `feed_requests`'e ON DELETE CASCADE bağlı; kökü silmek
                // ikisini de götürüyor.
                listOf(
                    "DELETE FROM recommendation_events WHERE user_id = ?",
                    "DELETE FROM feed_requests WHERE user_id = ?",
                ).forEach { sql ->
                    connection.prepareStatement(sql).use { statement ->
                        statement.setObject(1, userId)
                        statement.executeUpdate()
                    }
                }
                connection.commit()
            } catch (error: Throwable) {
                connection.rollback()
                throw error
            } finally {
                connection.autoCommit = true
            }
        }
    }

    override fun export(userId: UUID): RecommendationExport = dataSource.connection.use { connection ->
        RecommendationExport(
            events = connection.rows(
                """SELECT event_type, post_id, surface, position, dwell_millis, completion_ratio,
                          local_hour, timezone_offset_minutes, target_feature, candidate_source,
                          schema_version, app_version, platform, occurred_at
                   FROM recommendation_events WHERE user_id = ?
                   ORDER BY occurred_at""",
                userId,
            ),
            feedRequests = connection.rows(
                """SELECT id, requested_at, model_version, policy_version, feature_version,
                          experiment_variant, local_hour, personalized, candidate_count, returned_count
                   FROM feed_requests WHERE user_id = ? AND shadow_of IS NULL
                   ORDER BY requested_at""",
                userId,
            ),
            featureSnapshots = connection.rows(
                """SELECT feed_request_id, feature_version, captured_at, affinities, signal_count
                   FROM user_feature_snapshots WHERE user_id = ?
                   ORDER BY captured_at""",
                userId,
            ),
            hiddenPosts = connection.rows(
                "SELECT post_id, created_at FROM hidden_posts WHERE user_id = ? ORDER BY created_at",
                userId,
            ),
        )
    }

    /**
     * Saklama süresi silme işlemi partiler hâlinde yapılır: tek bir
     * `DELETE` milyonlarca satırı kilitleyip süpürme döngüsünü bloke ederdi.
     */
    override fun deleteOlderThan(cutoff: Instant, batchSize: Int): Int = dataSource.connection.use { connection ->
        var removed = 0
        listOf(
            """DELETE FROM recommendation_events WHERE id IN (
                   SELECT id FROM recommendation_events WHERE received_at < ? LIMIT ?
               )""",
            """DELETE FROM feed_requests WHERE id IN (
                   SELECT id FROM feed_requests WHERE requested_at < ? LIMIT ?
               )""",
        ).forEach { sql ->
            connection.prepareStatement(sql).use { statement ->
                statement.setTimestamp(1, Timestamp.from(cutoff))
                statement.setInt(2, batchSize)
                removed += statement.executeUpdate()
            }
        }
        removed
    }
}

/**
 * Satırları alan adı → metin eşlemesi olarak okur.
 *
 * Dışa aktarma her tablo için ayrı bir veri sınıfı gerektirmiyor: çıktı
 * kullanıcının indireceği JSON, ve şema değiştiğinde burayı da güncellemek
 * zorunda kalmak dışa aktarmayı sessizce eksik bırakma riski taşırdı.
 */
private fun java.sql.Connection.rows(sql: String, userId: UUID): List<Map<String, String?>> =
    prepareStatement(sql).use { statement ->
        statement.setObject(1, userId)
        statement.executeQuery().use { results ->
            val meta = results.metaData
            buildList {
                while (results.next()) {
                    add(
                        (1..meta.columnCount).associate { index ->
                            meta.getColumnLabel(index) to results.getObject(index)?.toString()
                        }
                    )
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
