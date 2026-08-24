package com.nexi.training

import com.nexi.config.DatabaseConfig
import com.nexi.config.DatabaseFactory
import com.nexi.feed.FeedCandidateRecord
import com.nexi.feed.FeedRequestRecord
import com.nexi.feed.JdbcFeedLineageRepository
import com.nexi.posts.CandidateSource
import com.nexi.recommendations.EventPlatform
import com.nexi.recommendations.JdbcRecommendationRepository
import com.nexi.recommendations.RecommendationEvent
import com.nexi.recommendations.RecommendationEventType
import com.zaxxer.hikari.HikariDataSource
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.testcontainers.postgresql.PostgreSQLContainer
import java.nio.file.Path
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import kotlin.io.path.readText
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Eğitim verisi dışa aktarımının etiketleme kuralları.
 *
 * SQL'in kendisi test ediliyor çünkü hatası sessiz: yanlış etiketlenmiş bir
 * satır hiçbir yerde hata vermez, yalnızca model yanlış öğrenir.
 */
class TrainingExportIntegrationTest {

    companion object {
        private val postgres = PostgreSQLContainer("postgres:17-alpine")
            .withDatabaseName("nexi_training")
            .withUsername("nexi")
            .withPassword("nexi_test_password")

        private lateinit var dataSource: HikariDataSource
        private lateinit var exportSql: String

        @JvmStatic
        @BeforeAll
        fun startPostgres() {
            postgres.start()
            dataSource = DatabaseFactory.create(
                DatabaseConfig(postgres.jdbcUrl, postgres.username, postgres.password)
            )
            // Betiği depodan okuyoruz: kopyasını test içine yazmak, ikisinin
            // ayrışmasına ve testin gerçekte koşulmayan bir SQL'i doğrulamasına
            // yol açardı. `psql` meta komutları burada çalışmıyor, o yüzden
            // `\if` bloğu sabit pencereyle değiştiriliyor.
            exportSql = Path.of("recommender-lab", "export_training_data.sql").readText()
                .substringAfter("\\endif")
                .replace(":window_hours", "24")
        }

        @JvmStatic
        @AfterAll
        fun stopPostgres() {
            if (::dataSource.isInitialized) dataSource.close()
            postgres.stop()
        }
    }

    private val lineage = JdbcFeedLineageRepository(dataSource)
    private val recommendations = JdbcRecommendationRepository(dataSource)
    private val now = Instant.parse("2026-08-23T12:00:00Z")

    private lateinit var userId: UUID
    private lateinit var postId: UUID

    @BeforeEach
    fun clean() {
        dataSource.connection.use { connection ->
            connection.createStatement().use {
                it.execute("TRUNCATE TABLE feed_requests, recommendation_events, posts, users CASCADE")
            }
        }
        userId = insertUser()
        postId = insertPost(userId)
    }

    @Test
    fun `an interaction inside the window labels the serving positive`() {
        val requestId = serve(at = now)
        interact(RecommendationEventType.CONTENT_LIKED, at = now.plusSeconds(3_600))

        val row = export().single()

        assertEquals(requestId.toString(), row["feed_request_id"])
        assertEquals("1", row["label"])
        assertEquals("0", row["negative"])
    }

    @Test
    fun `an interaction after the window does not label anything`() {
        // Üst sınır olmadan bir aylık beğeni, gönderinin bütün geçmiş
        // gösterimlerini olumlu etiketliyordu.
        serve(at = now)
        interact(RecommendationEventType.CONTENT_LIKED, at = now.plusSeconds(48 * 3_600))

        assertEquals("0", export().single()["label"])
    }

    @Test
    fun `one interaction is attributed to the nearest preceding serving only`() {
        // Gönderi sabah ve akşam iki kez gösterilip akşam beğenilirse, sabahki
        // gösterim de olumlu etiketlenmemeli; model "bu içerik sabah da iyi
        // gitti" diye öğrenirdi.
        val morning = serve(at = now)
        val evening = serve(at = now.plusSeconds(8 * 3_600))
        interact(RecommendationEventType.CONTENT_LIKED, at = now.plusSeconds(9 * 3_600))

        val labels = export().associate { it["feed_request_id"] to it["label"] }

        assertEquals("0", labels[morning.toString()], "sabahki gösterim etiketlenmemeli")
        assertEquals("1", labels[evening.toString()], "akşamki gösterim etiketlenmeli")
    }

    @Test
    fun `a short view is not a positive label`() {
        serve(at = now)
        interact(RecommendationEventType.CONTENT_VIEW, at = now.plusSeconds(60), dwellMillis = 900)

        assertEquals("0", export().single()["label"])
    }

    @Test
    fun `a long view is a positive label`() {
        serve(at = now)
        interact(RecommendationEventType.CONTENT_VIEW, at = now.plusSeconds(60), dwellMillis = 9_000)

        assertEquals("1", export().single()["label"])
    }

    @Test
    fun `hiding is recorded separately from the positive label`() {
        serve(at = now)
        interact(RecommendationEventType.CONTENT_HIDDEN, at = now.plusSeconds(120))

        val row = export().single()
        assertEquals("0", row["label"])
        assertEquals("1", row["negative"])
    }

    @Test
    fun `an unshown candidate never appears in the training set`() {
        // Gösterilmeyene kullanıcının tepki verme şansı hiç olmadı; onu
        // olumsuz saymak modeli yanlış eğitir.
        val other = insertPost(userId)
        serve(at = now, extraUnshown = other)

        val rows = export()

        assertEquals(1, rows.size)
        assertEquals(postId.toString(), rows.single()["post_id"])
    }

    @Test
    fun `shadow runs are excluded because nobody saw them`() {
        val served = serve(at = now)
        serve(at = now, shadowOf = served)

        assertEquals(1, export().size)
    }

    @Test
    fun `the exported columns match what the trainer reads`() {
        serve(at = now)

        val row = export().single()

        // `train_ranker.load_rows` bu adları arıyor; biri kayarsa hat sessizce
        // değil, yüksek sesle kırılmalı.
        listOf(
            "feed_request_id", "post_id", "candidate_source", "like_count", "comment_count",
            "age_hours", "media_type", "topic_slugs", "creator_id", "local_hour",
            "signal_count", "affinities", "label", "negative",
        ).forEach { column -> assertTrue(column in row, "eksik kolon: $column") }
    }

    // ------------------------------------------------------------ yardimcilar

    private fun export(): List<Map<String, String>> = dataSource.connection.use { connection ->
        connection.prepareStatement(exportSql).use { statement ->
            statement.executeQuery().use { results ->
                val meta = results.metaData
                buildList {
                    while (results.next()) {
                        add(
                            (1..meta.columnCount).associate { index ->
                                meta.getColumnLabel(index) to (results.getString(index) ?: "")
                            }
                        )
                    }
                }
            }
        }
    }

    private fun serve(at: Instant, shadowOf: UUID? = null, extraUnshown: UUID? = null): UUID {
        val requestId = UUID.randomUUID()
        val candidates = buildList {
            add(candidate(postId, position = if (shadowOf == null) 0 else null))
            extraUnshown?.let { add(candidate(it, position = null)) }
        }
        lineage.record(
            FeedRequestRecord(
                id = requestId,
                userId = userId,
                sessionId = UUID.randomUUID(),
                requestedAt = at,
                modelVersion = "nexi-contextual-v1",
                policyVersion = "nexi-policy-v2",
                featureVersion = "nexi-features-v2",
                experimentVariant = "heuristic",
                localHour = 12,
                timezoneOffsetMinutes = 180,
                personalized = true,
                shadowOf = shadowOf,
                candidates = candidates,
                affinities = mapOf("topic:teknoloji" to 0.4),
                signalCount = 2,
            )
        )
        return requestId
    }

    private fun candidate(post: UUID, position: Int?) = FeedCandidateRecord(
        postId = post,
        source = CandidateSource.DISCOVERY,
        rank = position ?: 0,
        rawScore = 0.2,
        finalScore = 0.8,
        position = position,
        reason = "test",
        likeCount = 1,
        commentCount = 0,
        ageHours = 2.0,
        mediaType = "text",
        topicSlugs = listOf("teknoloji"),
    )

    private fun interact(type: RecommendationEventType, at: Instant, dwellMillis: Long? = null) {
        recommendations.append(
            listOf(
                RecommendationEvent(
                    id = UUID.randomUUID(),
                    userId = userId,
                    postId = postId,
                    clientEventId = UUID.randomUUID(),
                    sessionId = UUID.randomUUID(),
                    feedRequestId = null,
                    eventType = type,
                    surface = "feed",
                    position = 0,
                    dwellMillis = dwellMillis,
                    completionRatio = null,
                    localHour = 12,
                    timezoneOffsetMinutes = 180,
                    targetFeature = null,
                    occurredAt = at,
                    receivedAt = at,
                    platform = EventPlatform.ANDROID,
                )
            )
        )
    }

    private fun insertUser(): UUID {
        val id = UUID.randomUUID()
        val stamp = Timestamp.from(now.minusSeconds(86_400))
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """INSERT INTO users
                   (id, full_name, username, username_normalized, email, email_normalized,
                    password_hash, email_verified_at, created_at, updated_at)
                   VALUES (?, 'Test', ?, ?, ?, ?, 'hash', ?, ?, ?)"""
            ).use { statement ->
                val name = "u${id.toString().replace("-", "").take(12)}"
                statement.setObject(1, id)
                statement.setString(2, name)
                statement.setString(3, name)
                statement.setString(4, "$name@example.test")
                statement.setString(5, "$name@example.test")
                statement.setTimestamp(6, stamp)
                statement.setTimestamp(7, stamp)
                statement.setTimestamp(8, stamp)
                statement.executeUpdate()
            }
        }
        return id
    }

    private fun insertPost(ownerId: UUID): UUID {
        val id = UUID.randomUUID()
        val stamp = Timestamp.from(now.minusSeconds(7_200))
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                "INSERT INTO posts (id, owner_id, body, status, created_at, updated_at) VALUES (?, ?, 'g', 'PUBLISHED', ?, ?)"
            ).use { statement ->
                statement.setObject(1, id)
                statement.setObject(2, ownerId)
                statement.setTimestamp(3, stamp)
                statement.setTimestamp(4, stamp)
                statement.executeUpdate()
            }
        }
        return id
    }
}
