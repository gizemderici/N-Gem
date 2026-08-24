package com.nexi.feed

import com.nexi.config.DatabaseConfig
import com.nexi.config.DatabaseFactory
import com.nexi.posts.CandidateSource
import com.nexi.recommendations.ConsentContract
import com.nexi.recommendations.JdbcConsentRepository
import com.zaxxer.hikari.HikariDataSource
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.testcontainers.postgresql.PostgreSQLContainer
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Soy kütüğü tablolarının gerçek SQL üzerinde yazıldığını doğrular.
 *
 * Bellek içi testler kaydın *içeriğini* kontrol ediyor; buradaki asıl soru
 * şemanın tuttuğu: JSONB profil anlık görüntüsü, `text[]` konu dizisi ve
 * gösterilmeyen adayın `NULL` konumu gerçekten yazılabiliyor mu.
 */
class FeedLineageIntegrationTest {

    companion object {
        private val postgres = PostgreSQLContainer("postgres:17-alpine")
            .withDatabaseName("nexi_lineage")
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

    private val repository = JdbcFeedLineageRepository(dataSource)
    private val now = Instant.parse("2026-08-23T12:00:00Z")

    @Test
    fun `a full feed request round trips through the lineage tables`() {
        val userId = insertUser("lineage_user")
        val servedPost = insertPost(userId)
        val skippedPost = insertPost(userId)
        val requestId = UUID.randomUUID()

        repository.record(
            FeedRequestRecord(
                id = requestId,
                userId = userId,
                sessionId = UUID.randomUUID(),
                requestedAt = now,
                modelVersion = "nexi-contextual-v1",
                policyVersion = FeedPolicy.POLICY_VERSION,
                featureVersion = "nexi-features-v2",
                experimentVariant = null,
                localHour = 12,
                timezoneOffsetMinutes = 180,
                personalized = true,
                candidates = listOf(
                    candidate(servedPost, CandidateSource.FOLLOWING, position = 0, likes = 4),
                    candidate(skippedPost, CandidateSource.DISCOVERY, position = null, likes = 0),
                ),
                affinities = mapOf("topic:teknoloji" to 0.42, "media:text" to -0.1),
                signalCount = 7,
            )
        )

        dataSource.connection.use { connection ->
            connection.prepareStatement(
                "SELECT candidate_count, returned_count, policy_version FROM feed_requests WHERE id = ?"
            ).use { statement ->
                statement.setObject(1, requestId)
                statement.executeQuery().use { results ->
                    assertTrue(results.next())
                    assertEquals(2, results.getInt("candidate_count"))
                    assertEquals(1, results.getInt("returned_count"))
                    assertEquals(FeedPolicy.POLICY_VERSION, results.getString("policy_version"))
                }
            }

            connection.prepareStatement(
                """SELECT post_id, candidate_source, position, like_count, topic_slugs
                   FROM feed_candidates WHERE feed_request_id = ? ORDER BY position NULLS LAST"""
            ).use { statement ->
                statement.setObject(1, requestId)
                statement.executeQuery().use { results ->
                    assertTrue(results.next())
                    assertEquals(servedPost, results.getObject("post_id", UUID::class.java))
                    assertEquals("FOLLOWING", results.getString("candidate_source"))
                    assertEquals(0, results.getInt("position"))
                    assertEquals(4L, results.getLong("like_count"))
                    assertEquals(
                        listOf("teknoloji"),
                        (results.getArray("topic_slugs").array as Array<*>).filterIsInstance<String>(),
                    )

                    assertTrue(results.next())
                    results.getInt("position")
                    assertTrue(results.wasNull(), "gösterilmeyen adayın konumu NULL olmalı")
                }
            }

            connection.prepareStatement(
                """SELECT signal_count, affinities ->> 'topic:teknoloji' AS teknoloji
                   FROM user_feature_snapshots WHERE feed_request_id = ?"""
            ).use { statement ->
                statement.setObject(1, requestId)
                statement.executeQuery().use { results ->
                    assertTrue(results.next())
                    assertEquals(7, results.getInt("signal_count"))
                    assertEquals(0.42, results.getString("teknoloji").toDouble())
                }
            }
        }
    }

    @Test
    fun `consent defaults to none and survives a round trip`() {
        val userId = insertUser("consent_user")
        val consents = JdbcConsentRepository(dataSource)

        // Kaydı olmayan kullanıcı için varsayılan rıza yok; sessizce "açık"
        // saymak kullanıcıyı hiç sorulmadan profillemek olurdu.
        val initial = consents.find(userId)
        assertEquals(false, initial.granted)
        assertNull(initial.updatedAt)

        consents.set(userId, granted = true, contractVersion = ConsentContract.VERSION, now = now)
        assertEquals(true, consents.find(userId).granted)

        consents.set(userId, granted = false, contractVersion = ConsentContract.VERSION, now = now)
        assertEquals(false, consents.find(userId).granted)
    }

    private fun candidate(postId: UUID, source: CandidateSource, position: Int?, likes: Long) =
        FeedCandidateRecord(
            postId = postId,
            source = source,
            rank = position ?: 1,
            rawScore = 0.31,
            finalScore = 1.24,
            position = position,
            reason = "Bu saatte Teknoloji ilgine uygun",
            likeCount = likes,
            commentCount = 0,
            ageHours = 2.5,
            mediaType = "text",
            topicSlugs = listOf("teknoloji"),
        )

    private fun insertUser(username: String): UUID {
        val id = UUID.randomUUID()
        val timestamp = Timestamp.from(now.minusSeconds(3_600))
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """INSERT INTO users
                   (id, full_name, username, username_normalized, email, email_normalized,
                    password_hash, email_verified_at, created_at, updated_at)
                   VALUES (?, ?, ?, ?, ?, ?, 'hash', ?, ?, ?)"""
            ).use { statement ->
                val email = "$username@example.test"
                statement.setObject(1, id)
                statement.setString(2, username)
                statement.setString(3, username)
                statement.setString(4, username)
                statement.setString(5, email)
                statement.setString(6, email)
                statement.setTimestamp(7, timestamp)
                statement.setTimestamp(8, timestamp)
                statement.setTimestamp(9, timestamp)
                statement.executeUpdate()
            }
        }
        return id
    }

    private fun insertPost(ownerId: UUID): UUID {
        val id = UUID.randomUUID()
        val timestamp = Timestamp.from(now.minusSeconds(600))
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                "INSERT INTO posts (id, owner_id, body, status, created_at, updated_at) VALUES (?, ?, 'gonderi', 'PUBLISHED', ?, ?)"
            ).use { statement ->
                statement.setObject(1, id)
                statement.setObject(2, ownerId)
                statement.setTimestamp(3, timestamp)
                statement.setTimestamp(4, timestamp)
                statement.executeUpdate()
            }
        }
        return id
    }
}
