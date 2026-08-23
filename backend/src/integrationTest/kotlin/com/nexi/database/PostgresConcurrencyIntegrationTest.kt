package com.nexi.database

import com.nexi.auth.JdbcAuthRepository
import com.nexi.config.DatabaseConfig
import com.nexi.config.DatabaseFactory
import com.nexi.messaging.JdbcMessagingRepository
import com.nexi.moderation.JdbcModerationRepository
import com.nexi.moderation.Report
import com.nexi.moderation.ReportReason
import com.nexi.moderation.ReportStatus
import com.nexi.moderation.ReportTargetType
import com.nexi.notifications.JdbcNotificationRepository
import com.nexi.notifications.Notification
import com.nexi.notifications.NotificationTargetType
import com.nexi.notifications.NotificationType
import com.nexi.posts.JdbcPostRepository
import com.nexi.profiles.JdbcProfileRepository
import com.nexi.stories.JdbcStoryRepository
import com.zaxxer.hikari.HikariDataSource
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.testcontainers.postgresql.PostgreSQLContainer
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PostgresConcurrencyIntegrationTest {

    companion object {
        private val postgres = PostgreSQLContainer("postgres:17-alpine")
            .withDatabaseName("nexi_integration")
            .withUsername("nexi")
            .withPassword("nexi_test_password")

        private lateinit var dataSource: HikariDataSource

        @JvmStatic
        @BeforeAll
        fun startPostgres() {
            postgres.start()
            dataSource = DatabaseFactory.create(
                DatabaseConfig(
                    url = postgres.jdbcUrl,
                    user = postgres.username,
                    password = postgres.password,
                )
            )
        }

        @JvmStatic
        @AfterAll
        fun stopPostgres() {
            if (::dataSource.isInitialized) dataSource.close()
            postgres.stop()
        }
    }

    @BeforeEach
    fun cleanApplicationTables() {
        dataSource.connection.use { connection ->
            val tables = connection.prepareStatement(
                """SELECT tablename FROM pg_tables
                   WHERE schemaname = 'public' AND tablename <> 'flyway_schema_history'"""
            ).use { statement ->
                statement.executeQuery().use { results ->
                    buildList { while (results.next()) add(results.getString(1)) }
                }
            }
            if (tables.isNotEmpty()) {
                val quoted = tables.joinToString(", ") { "\"${it.replace("\"", "\"\"")}\"" }
                connection.createStatement().use { it.execute("TRUNCATE TABLE $quoted CASCADE") }
            }
        }
    }

    @Test
    fun `all Flyway migrations apply to a real PostgreSQL 17 database`() {
        val versions = dataSource.connection.use { connection ->
            connection.prepareStatement(
                "SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank"
            ).use { statement ->
                statement.executeQuery().use { results ->
                    buildList { while (results.next()) add(results.getString(1)) }
                }
            }
        }

        assertEquals((1..15).map(Int::toString), versions)
    }

    @Test
    fun `a refresh token can be consumed by only one concurrent request`() {
        val userId = insertUser("refresh_user")
        val tokenHash = "a".repeat(64)
        val now = Instant.parse("2026-08-23T12:00:00Z")
        val repository = JdbcAuthRepository(dataSource)
        repository.createRefreshSession(
            id = UUID.randomUUID(),
            userId = userId,
            tokenHash = tokenHash,
            expiresAt = now.plusSeconds(3600),
            createdAt = now,
        )

        val claimedUsers = runConcurrently { index ->
            repository.takeRefreshSession(tokenHash, now.plusMillis(index.toLong()))
        }

        assertEquals(listOf(userId), claimedUsers.filterNotNull())
        assertEquals(1L, rowCount("refresh_sessions", "revoked_at IS NOT NULL"))
    }

    @Test
    fun `concurrent follow like and save requests stay idempotent`() {
        val authorId = insertUser("interaction_author")
        val viewerId = insertUser("interaction_viewer")
        val postId = insertPost(authorId)
        val now = Instant.parse("2026-08-23T12:10:00Z")
        val profiles = JdbcProfileRepository(dataSource)
        val posts = JdbcPostRepository(dataSource)

        runConcurrently { profiles.setFollow(viewerId, authorId, active = true, now) }
        runConcurrently { posts.setLike(postId, viewerId, active = true, now) }
        runConcurrently { posts.setSave(postId, viewerId, active = true, now) }

        assertEquals(1L, profiles.followerCount(authorId))
        assertEquals(1L, rowCount("follows"))
        assertEquals(1L, rowCount("post_likes"))
        assertEquals(1L, rowCount("post_saves"))
    }

    @Test
    fun `concurrent direct conversation creation returns one conversation`() {
        val firstUser = insertUser("message_first")
        val secondUser = insertUser("message_second")
        val now = Instant.parse("2026-08-23T12:20:00Z")
        val repository = JdbcMessagingRepository(dataSource)

        val conversationIds = runConcurrently { index ->
            if (index % 2 == 0) {
                repository.findOrCreateDirect(firstUser, secondUser, now)
            } else {
                repository.findOrCreateDirect(secondUser, firstUser, now)
            }
        }

        assertEquals(1, conversationIds.toSet().size)
        assertEquals(1L, rowCount("conversations"))
        assertEquals(2L, rowCount("conversation_members"))
    }

    @Test
    fun `one media asset can be attached to only one concurrent post`() {
        val ownerId = insertUser("media_owner")
        val mediaId = insertReadyMedia(ownerId)
        val now = Instant.parse("2026-08-23T12:30:00Z")
        val repository = JdbcPostRepository(dataSource)

        val attempts = runConcurrently(workers = 2) { index ->
            runCatching {
                repository.create(
                    ownerId = ownerId,
                    body = "concurrent post $index",
                    mediaIds = listOf(mediaId),
                    topicIds = emptyList(),
                    now = now.plusMillis(index.toLong()),
                )
            }
        }

        assertEquals(1, attempts.count { it.isSuccess })
        assertEquals(1, attempts.count { it.isFailure })
        assertEquals(1L, rowCount("posts"))
        assertEquals(1L, rowCount("post_media"))
    }

    @Test
    fun `concurrent duplicate notifications produce one unread row`() {
        val recipientId = insertUser("notification_recipient")
        val actorId = insertUser("notification_actor")
        val postId = insertPost(recipientId)
        val now = Instant.parse("2026-08-23T12:40:00Z")
        val repository = JdbcNotificationRepository(dataSource)

        runConcurrently { index ->
            repository.emit(
                Notification(
                    id = UUID.randomUUID(),
                    userId = recipientId,
                    actorId = actorId,
                    type = NotificationType.POST_LIKE,
                    targetType = NotificationTargetType.POST,
                    targetId = postId,
                    readAt = null,
                    createdAt = now.plusMillis(index.toLong()),
                )
            )
        }

        assertEquals(1L, rowCount("notifications"))
        assertEquals(1L, repository.unreadCount(recipientId))
    }

    @Test
    fun `concurrent duplicate reports return one report and one audit event`() {
        val reporterId = insertUser("reporter")
        val targetId = insertUser("reported_user")
        val now = Instant.parse("2026-08-23T12:50:00Z")
        val repository = JdbcModerationRepository(dataSource)

        val results = runConcurrently { index ->
            repository.createReport(
                Report(
                    id = UUID.randomUUID(),
                    reporterId = reporterId,
                    targetType = ReportTargetType.USER,
                    targetId = targetId,
                    reason = ReportReason.SPAM,
                    details = "concurrent report $index",
                    status = ReportStatus.OPEN,
                    createdAt = now.plusMillis(index.toLong()),
                    updatedAt = now.plusMillis(index.toLong()),
                )
            )
        }

        assertEquals(1, results.count { (_, alreadyReported) -> !alreadyReported })
        assertEquals(7, results.count { (_, alreadyReported) -> alreadyReported })
        assertEquals(1, results.map { it.first.id }.toSet().size)
        assertEquals(1L, rowCount("reports"))
        assertEquals(1L, rowCount("report_events"))
    }

    @Test
    fun `concurrent story views preserve the first view only`() {
        val ownerId = insertUser("story_owner")
        val viewerId = insertUser("story_viewer")
        val mediaId = insertReadyMedia(ownerId)
        val storyId = insertStory(ownerId, mediaId)
        val now = Instant.parse("2026-08-23T13:00:00Z")
        val repository = JdbcStoryRepository(dataSource)

        val inserted = runConcurrently { index ->
            repository.markViewed(storyId, viewerId, now.plusMillis(index.toLong()))
        }

        assertEquals(1, inserted.count { it })
        assertEquals(7, inserted.count { !it })
        assertEquals(1L, repository.viewerCount(storyId))
        assertEquals(1L, rowCount("story_views"))
    }

    private fun insertUser(username: String): UUID {
        val id = UUID.randomUUID()
        val now = Timestamp.from(Instant.parse("2026-08-23T10:00:00Z"))
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """INSERT INTO users
                   (id, full_name, username, username_normalized, email, email_normalized,
                    password_hash, email_verified_at, created_at, updated_at)
                   VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"""
            ).use { statement ->
                val email = "$username@example.test"
                statement.setObject(1, id)
                statement.setString(2, username.replace('_', ' '))
                statement.setString(3, username)
                statement.setString(4, username.lowercase())
                statement.setString(5, email)
                statement.setString(6, email.lowercase())
                statement.setString(7, "integration-test-password-hash")
                statement.setTimestamp(8, now)
                statement.setTimestamp(9, now)
                statement.setTimestamp(10, now)
                statement.executeUpdate()
            }
        }
        return id
    }

    private fun insertPost(ownerId: UUID): UUID {
        val id = UUID.randomUUID()
        val now = Timestamp.from(Instant.parse("2026-08-23T10:10:00Z"))
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                "INSERT INTO posts (id, owner_id, body, status, created_at, updated_at) VALUES (?, ?, ?, 'PUBLISHED', ?, ?)"
            ).use { statement ->
                statement.setObject(1, id)
                statement.setObject(2, ownerId)
                statement.setString(3, "integration test post")
                statement.setTimestamp(4, now)
                statement.setTimestamp(5, now)
                statement.executeUpdate()
            }
        }
        return id
    }

    private fun insertReadyMedia(ownerId: UUID): UUID {
        val id = UUID.randomUUID()
        val now = Timestamp.from(Instant.parse("2026-08-23T10:20:00Z"))
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """INSERT INTO media_assets
                   (id, owner_id, storage_key, original_filename, mime_type, declared_size_bytes,
                    actual_size_bytes, status, processing_status, created_at, updated_at)
                   VALUES (?, ?, ?, 'test.jpg', 'image/jpeg', 100, 100, 'READY', 'READY', ?, ?)"""
            ).use { statement ->
                statement.setObject(1, id)
                statement.setObject(2, ownerId)
                statement.setString(3, "integration/$id.jpg")
                statement.setTimestamp(4, now)
                statement.setTimestamp(5, now)
                statement.executeUpdate()
            }
        }
        return id
    }

    private fun insertStory(ownerId: UUID, mediaId: UUID): UUID {
        val id = UUID.randomUUID()
        val publishedAt = Instant.parse("2026-08-23T10:30:00Z")
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """INSERT INTO stories
                   (id, owner_id, media_id, caption, status, published_at, expires_at, created_at, updated_at)
                   VALUES (?, ?, ?, 'integration story', 'PUBLISHED', ?, ?, ?, ?)"""
            ).use { statement ->
                statement.setObject(1, id)
                statement.setObject(2, ownerId)
                statement.setObject(3, mediaId)
                statement.setTimestamp(4, Timestamp.from(publishedAt))
                statement.setTimestamp(5, Timestamp.from(publishedAt.plusSeconds(86_400)))
                statement.setTimestamp(6, Timestamp.from(publishedAt))
                statement.setTimestamp(7, Timestamp.from(publishedAt))
                statement.executeUpdate()
            }
        }
        return id
    }

    private fun rowCount(table: String, where: String? = null): Long {
        val allowedTables = setOf(
            "refresh_sessions", "follows", "post_likes", "post_saves", "conversations",
            "conversation_members", "posts", "post_media", "notifications", "reports",
            "report_events", "story_views",
        )
        require(table in allowedTables)
        val suffix = where?.let { " WHERE $it" }.orEmpty()
        return dataSource.connection.use { connection ->
            connection.prepareStatement("SELECT COUNT(*) FROM $table$suffix").use { statement ->
                statement.executeQuery().use { results -> results.next(); results.getLong(1) }
            }
        }
    }

    private fun <T> runConcurrently(workers: Int = 8, action: (Int) -> T): List<T> {
        val executor = Executors.newFixedThreadPool(workers)
        val ready = CountDownLatch(workers)
        val start = CountDownLatch(1)
        return try {
            val futures = (0 until workers).map { index ->
                executor.submit<T> {
                    ready.countDown()
                    assertTrue(start.await(10, TimeUnit.SECONDS), "Concurrent start signal timed out")
                    action(index)
                }
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS), "Workers did not become ready")
            start.countDown()
            futures.map { it.get(30, TimeUnit.SECONDS) }
        } finally {
            start.countDown()
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS), "Executor did not stop")
        }
    }
}
