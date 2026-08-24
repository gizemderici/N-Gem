package com.nexi.privacy

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
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Kullanıcı kendi verisini görebilmeli, indirebilmeli ve silebilmeli;
 * saklama süresi geçen veri otomatik temizlenmeli.
 *
 * Bunlar gerçek SQL gerektiriyor: silmenin `feed_candidates` ve
 * `user_feature_snapshots`'a kadar yayılıp yayılmadığı ancak `ON DELETE
 * CASCADE` gerçekten koştuğunda görülür.
 */
class PersonalDataIntegrationTest {

    companion object {
        private val postgres = PostgreSQLContainer("postgres:17-alpine")
            .withDatabaseName("nexi_privacy")
            .withUsername("nexi")
            .withPassword("nexi_test_password")

        private lateinit var dataSource: HikariDataSource

        @JvmStatic
        @BeforeAll
        fun startPostgres() {
            postgres.start()
            dataSource = DatabaseFactory.create(
                DatabaseConfig(postgres.jdbcUrl, postgres.username, postgres.password)
            )
        }

        @JvmStatic
        @AfterAll
        fun stopPostgres() {
            if (::dataSource.isInitialized) dataSource.close()
            postgres.stop()
        }
    }

    private val recommendations = JdbcRecommendationRepository(dataSource)
    private val lineage = JdbcFeedLineageRepository(dataSource)
    private val now = Instant.parse("2026-08-23T12:00:00Z")

    private lateinit var userId: UUID
    private lateinit var postId: UUID

    @BeforeEach
    fun seed() {
        userId = insertUser()
        postId = insertPost(userId)
        recommendations.append(listOf(event(userId, postId, now)))
        recommendations.hide(userId, postId, now)
        recordRequest(userId, postId, now)
    }

    @Test
    fun `the export hands back every row the profile is built from`() {
        val export = recommendations.export(userId)

        assertEquals(1, export.events.size)
        assertEquals(1, export.feedRequests.size)
        assertEquals(1, export.featureSnapshots.size)
        assertEquals(1, export.hiddenPosts.size)
        assertEquals("CONTENT_VIEW", export.events.single()["event_type"])
        // Profil anlık görüntüsü de indirilebilmeli: "ne öğrendik" sorusunun
        // cevabı özet değil, kaydın kendisi.
        assertTrue(export.featureSnapshots.single()["affinities"]!!.contains("topic:teknoloji"))
    }

    @Test
    fun `the export includes the candidates that produced the ranking`() {
        // Erişim hakkının asıl karşılığı burası: "neden bu içeriği gördüm"
        // sorusu ancak değerlendirilen adaylar ve puanları görülünce
        // cevaplanabilir. Dışa aktarma bunları hiç içermiyordu.
        val candidate = recommendations.export(userId).feedCandidates.single()

        assertEquals(postId.toString(), candidate["post_id"])
        assertEquals("DISCOVERY", candidate["candidate_source"])
        assertEquals("0", candidate["position"])
        assertEquals("teknoloji", candidate["topic_slugs"])
        assertTrue(candidate["final_score"] != null)
    }

    @Test
    fun `the export includes shadow rankings, not just what was shown`() {
        // Gölge kayıtları dışarıda bırakmak, kullanıcı hakkında tutulan
        // verinin bir bölümünü gizlemek olurdu.
        recordShadowRequest(userId, postId, now)

        val requests = recommendations.export(userId).feedRequests

        assertEquals(2, requests.size)
        assertEquals(1, requests.count { it["shadow_of"] != null }, "gölge kaydı da dönmeli")
        assertEquals(1, requests.count { it["shadow_of"] == null })
    }

    @Test
    fun `resetting the profile removes the lineage too, not just the events`() {
        // Eskiden yalnızca olaylar siliniyordu; kullanıcı "sildim" dediği veri
        // feed_requests ve user_feature_snapshots içinde kalıp eğitim setine
        // girmeye devam ediyordu.
        recommendations.clear(userId)

        assertEquals(0, count("recommendation_events", userId))
        assertEquals(0, count("feed_requests", userId))
        assertEquals(0, count("user_feature_snapshots", userId))
        assertEquals(0, countCandidates())

        val export = recommendations.export(userId)
        assertTrue(export.events.isEmpty())
        assertTrue(export.feedRequests.isEmpty())
        assertTrue(export.featureSnapshots.isEmpty())
    }

    @Test
    fun `resetting keeps the posts the user chose to hide`() {
        // Gizleme öğrenilmiş profil değil, açık tercih. Sıfırlamayla silinseydi
        // kullanıcının gizlediği içerik akışa geri dönerdi.
        recommendations.clear(userId)

        assertEquals(1, count("hidden_posts", userId))
    }

    @Test
    fun `retention removes what is older than the cutoff and keeps the rest`() {
        val oldUser = insertUser()
        val oldPost = insertPost(oldUser)
        val old = now.minusSeconds(400L * 86_400)
        recommendations.append(listOf(event(oldUser, oldPost, old)))
        recordRequest(oldUser, oldPost, old)

        val removed = recommendations.deleteOlderThan(now.minusSeconds(180L * 86_400), 1_000)

        assertTrue(removed >= 2, "eski olay ve istek silinmeli, silinen: $removed")
        assertEquals(0, count("recommendation_events", oldUser))
        assertEquals(0, count("feed_requests", oldUser))
        // Süresi dolmamış kullanıcının verisi yerinde.
        assertEquals(1, count("recommendation_events", userId))
        assertEquals(1, count("feed_requests", userId))
    }

    @Test
    fun `retention works in batches so one sweep cannot lock the table`() {
        val oldUser = insertUser()
        val oldPost = insertPost(oldUser)
        val old = now.minusSeconds(400L * 86_400)
        repeat(5) { index -> recommendations.append(listOf(event(oldUser, oldPost, old, index))) }

        val cutoff = now.minusSeconds(180L * 86_400)
        recommendations.deleteOlderThan(cutoff, 2)

        assertEquals(3, count("recommendation_events", oldUser), "parti boyutuna uyulmalı")
    }

    // ------------------------------------------------------------ yardimcilar

    private fun event(user: UUID, post: UUID, at: Instant, seed: Int = 0) = RecommendationEvent(
        id = UUID.randomUUID(),
        userId = user,
        postId = post,
        clientEventId = UUID.nameUUIDFromBytes("$user-$at-$seed".toByteArray()),
        sessionId = UUID.randomUUID(),
        feedRequestId = null,
        eventType = RecommendationEventType.CONTENT_VIEW,
        surface = "feed",
        position = 0,
        dwellMillis = 8_000,
        completionRatio = null,
        localHour = 12,
        timezoneOffsetMinutes = 180,
        targetFeature = null,
        occurredAt = at,
        receivedAt = at,
        platform = EventPlatform.IOS,
    )

    /** Gösterilmeyen gölge koşusu; asıl isteğe bağlı, hiçbir adayın konumu yok. */
    private fun recordShadowRequest(user: UUID, post: UUID, at: Instant) {
        val servedId = dataSource.connection.use { connection ->
            connection.prepareStatement(
                "SELECT id FROM feed_requests WHERE user_id = ? AND shadow_of IS NULL LIMIT 1"
            ).use { statement ->
                statement.setObject(1, user)
                statement.executeQuery().use { results ->
                    results.next()
                    results.getObject(1, UUID::class.java)
                }
            }
        }
        recordRequest(user, post, at, shadowOf = servedId, position = null)
    }

    private fun recordRequest(
        user: UUID,
        post: UUID,
        at: Instant,
        shadowOf: UUID? = null,
        position: Int? = 0,
    ) {
        lineage.record(
            FeedRequestRecord(
                id = UUID.randomUUID(),
                userId = user,
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
                candidates = listOf(
                    FeedCandidateRecord(
                        postId = post,
                        source = CandidateSource.DISCOVERY,
                        rank = position ?: 0,
                        rawScore = 0.1,
                        finalScore = 0.9,
                        position = position,
                        reason = "test",
                        likeCount = 0,
                        commentCount = 0,
                        ageHours = 1.0,
                        mediaType = "text",
                        topicSlugs = listOf("teknoloji"),
                    )
                ),
                affinities = mapOf("topic:teknoloji" to 0.5),
                signalCount = 3,
            )
        )
    }

    private fun count(table: String, user: UUID): Int {
        require(table in setOf("recommendation_events", "feed_requests", "user_feature_snapshots", "hidden_posts"))
        return dataSource.connection.use { connection ->
            connection.prepareStatement("SELECT COUNT(*) FROM $table WHERE user_id = ?").use { statement ->
                statement.setObject(1, user)
                statement.executeQuery().use { results -> results.next(); results.getInt(1) }
            }
        }
    }

    private fun countCandidates(): Int = dataSource.connection.use { connection ->
        connection.prepareStatement(
            """SELECT COUNT(*) FROM feed_candidates fc
               WHERE NOT EXISTS (SELECT 1 FROM feed_requests fr WHERE fr.id = fc.feed_request_id)"""
        ).use { statement ->
            statement.executeQuery().use { results -> results.next(); results.getInt(1) }
        }
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
        val stamp = Timestamp.from(now.minusSeconds(3_600))
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
