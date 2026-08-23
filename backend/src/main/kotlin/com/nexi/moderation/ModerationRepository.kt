package com.nexi.moderation

import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

interface ModerationRepository {
    /**
     * Engeli açar veya kapatır. Engelleme sırasında iki kullanıcı arasındaki
     * takip ilişkisi **her iki yönde de** silinir; aksi halde engellenen kişi
     * takipçi listesinde durmaya devam ederdi.
     */
    fun setBlock(blockerId: UUID, blockedId: UUID, active: Boolean, now: Instant)

    fun isBlockedEitherWay(userId: UUID, otherId: UUID): Boolean

    fun blockedUsers(blockerId: UUID, cursor: BlockCursor?, limit: Int): List<BlockedUser>
    fun blockedCount(blockerId: UUID): Long

    /** Aynı hedef daha önce şikâyet edildiyse mevcut kaydı döner, yenisini yazmaz. */
    fun createReport(report: Report): Pair<Report, Boolean>
    fun reports(reporterId: UUID, cursor: Instant?, cursorId: UUID?, limit: Int): List<Report>
    fun reportCount(reporterId: UUID): Long

    fun targetExists(type: ReportTargetType, targetId: UUID): Boolean
}

class JdbcModerationRepository(private val dataSource: DataSource) : ModerationRepository {

    override fun setBlock(blockerId: UUID, blockedId: UUID, active: Boolean, now: Instant) {
        dataSource.connection.use { connection ->
            connection.autoCommit = false
            try {
                if (active) {
                    connection.prepareStatement(
                        "INSERT INTO user_blocks (blocker_id, blocked_id, created_at) VALUES (?, ?, ?) ON CONFLICT DO NOTHING"
                    ).use { statement ->
                        statement.setObject(1, blockerId)
                        statement.setObject(2, blockedId)
                        statement.setTimestamp(3, Timestamp.from(now))
                        statement.executeUpdate()
                    }
                    // Karşılıklı takip ilişkisini kaldır.
                    connection.prepareStatement(
                        """DELETE FROM follows
                           WHERE (follower_id = ? AND followee_id = ?)
                              OR (follower_id = ? AND followee_id = ?)"""
                    ).use { statement ->
                        statement.setObject(1, blockerId)
                        statement.setObject(2, blockedId)
                        statement.setObject(3, blockedId)
                        statement.setObject(4, blockerId)
                        statement.executeUpdate()
                    }
                } else {
                    connection.prepareStatement(
                        "DELETE FROM user_blocks WHERE blocker_id = ? AND blocked_id = ?"
                    ).use { statement ->
                        statement.setObject(1, blockerId)
                        statement.setObject(2, blockedId)
                        statement.executeUpdate()
                    }
                }
                connection.commit()
            } catch (exception: Throwable) {
                connection.rollback()
                throw exception
            } finally {
                connection.autoCommit = true
            }
        }
    }

    override fun isBlockedEitherWay(userId: UUID, otherId: UUID): Boolean =
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """SELECT 1 FROM user_blocks
                   WHERE (blocker_id = ? AND blocked_id = ?) OR (blocker_id = ? AND blocked_id = ?)
                   LIMIT 1"""
            ).use { statement ->
                statement.setObject(1, userId)
                statement.setObject(2, otherId)
                statement.setObject(3, otherId)
                statement.setObject(4, userId)
                statement.executeQuery().use { it.next() }
            }
        }

    override fun blockedUsers(blockerId: UUID, cursor: BlockCursor?, limit: Int): List<BlockedUser> =
        dataSource.connection.use { connection ->
            val cursorClause = if (cursor == null) "" else "AND (ub.created_at, u.id) < (?, ?)"
            connection.prepareStatement(
                """SELECT u.id, u.full_name, u.username, ub.created_at, am.storage_key AS avatar_key
                   FROM user_blocks ub
                   JOIN users u ON u.id = ub.blocked_id
                   LEFT JOIN media_assets am ON am.id = u.avatar_media_id AND am.status = 'READY'
                   WHERE ub.blocker_id = ? $cursorClause
                   ORDER BY ub.created_at DESC, u.id DESC LIMIT ?"""
            ).use { statement ->
                var index = 1
                statement.setObject(index++, blockerId)
                if (cursor != null) {
                    statement.setTimestamp(index++, Timestamp.from(cursor.createdAt))
                    statement.setObject(index++, cursor.userId)
                }
                statement.setInt(index, limit)
                statement.executeQuery().use { results ->
                    buildList {
                        while (results.next()) {
                            add(
                                BlockedUser(
                                    userId = results.getObject("id", UUID::class.java),
                                    fullName = results.getString("full_name"),
                                    username = results.getString("username"),
                                    avatarStorageKey = results.getString("avatar_key"),
                                    createdAt = results.getTimestamp("created_at").toInstant(),
                                )
                            )
                        }
                    }
                }
            }
        }

    override fun blockedCount(blockerId: UUID): Long = dataSource.connection.use { connection ->
        connection.prepareStatement("SELECT COUNT(*) FROM user_blocks WHERE blocker_id = ?").use { statement ->
            statement.setObject(1, blockerId)
            statement.executeQuery().use { results -> results.next(); results.getLong(1) }
        }
    }

    override fun createReport(report: Report): Pair<Report, Boolean> = dataSource.connection.use { connection ->
        val inserted = connection.prepareStatement(
            """INSERT INTO reports
               (id, reporter_id, target_type, target_id, reason, details, status, created_at, updated_at)
               VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
               ON CONFLICT (reporter_id, target_type, target_id) DO NOTHING"""
        ).use { statement ->
            statement.setObject(1, report.id)
            statement.setObject(2, report.reporterId)
            statement.setString(3, report.targetType.name)
            statement.setObject(4, report.targetId)
            statement.setString(5, report.reason.name)
            statement.setString(6, report.details)
            statement.setString(7, report.status.name)
            statement.setTimestamp(8, Timestamp.from(report.createdAt))
            statement.setTimestamp(9, Timestamp.from(report.updatedAt))
            statement.executeUpdate() == 1
        }

        if (inserted) {
            appendEvent(connection, report.id, report.status, null, null, report.createdAt)
            return@use report to false
        }

        // Zaten şikâyet edilmiş: mevcut kaydı döndür.
        val existing = connection.prepareStatement(
            "${reportSelect()} WHERE reporter_id = ? AND target_type = ? AND target_id = ?"
        ).use { statement ->
            statement.setObject(1, report.reporterId)
            statement.setString(2, report.targetType.name)
            statement.setObject(3, report.targetId)
            statement.executeQuery().use { results ->
                if (results.next()) results.toReport() else null
            }
        }
        (existing ?: report) to true
    }

    override fun reports(reporterId: UUID, cursor: Instant?, cursorId: UUID?, limit: Int): List<Report> =
        dataSource.connection.use { connection ->
            val cursorClause = if (cursor == null) "" else "AND (created_at, id) < (?, ?)"
            connection.prepareStatement(
                "${reportSelect()} WHERE reporter_id = ? $cursorClause ORDER BY created_at DESC, id DESC LIMIT ?"
            ).use { statement ->
                var index = 1
                statement.setObject(index++, reporterId)
                if (cursor != null) {
                    statement.setTimestamp(index++, Timestamp.from(cursor))
                    statement.setObject(index++, cursorId)
                }
                statement.setInt(index, limit)
                statement.executeQuery().use { results ->
                    buildList { while (results.next()) add(results.toReport()) }
                }
            }
        }

    override fun reportCount(reporterId: UUID): Long = dataSource.connection.use { connection ->
        connection.prepareStatement("SELECT COUNT(*) FROM reports WHERE reporter_id = ?").use { statement ->
            statement.setObject(1, reporterId)
            statement.executeQuery().use { results -> results.next(); results.getLong(1) }
        }
    }

    override fun targetExists(type: ReportTargetType, targetId: UUID): Boolean {
        // Tablo adı enum'dan geliyor, kullanıcı girdisinden değil.
        val query = when (type) {
            ReportTargetType.USER -> "SELECT 1 FROM users WHERE id = ?"
            ReportTargetType.POST -> "SELECT 1 FROM posts WHERE id = ? AND status = 'PUBLISHED'"
            ReportTargetType.COMMENT -> "SELECT 1 FROM comments WHERE id = ? AND status = 'PUBLISHED'"
            // Hikâyeler ve mesajlar henüz yok; servis buraya gelmeden reddediyor.
            ReportTargetType.STORY, ReportTargetType.MESSAGE -> return false
        }
        return dataSource.connection.use { connection ->
            connection.prepareStatement(query).use { statement ->
                statement.setObject(1, targetId)
                statement.executeQuery().use { it.next() }
            }
        }
    }

    private fun appendEvent(
        connection: java.sql.Connection,
        reportId: UUID,
        status: ReportStatus,
        note: String?,
        actorId: UUID?,
        now: Instant,
    ) {
        connection.prepareStatement(
            "INSERT INTO report_events (id, report_id, status, note, actor_id, created_at) VALUES (?, ?, ?, ?, ?, ?)"
        ).use { statement ->
            statement.setObject(1, UUID.randomUUID())
            statement.setObject(2, reportId)
            statement.setString(3, status.name)
            statement.setString(4, note)
            statement.setObject(5, actorId)
            statement.setTimestamp(6, Timestamp.from(now))
            statement.executeUpdate()
        }
    }

    private fun reportSelect() =
        "SELECT id, reporter_id, target_type, target_id, reason, details, status, created_at, updated_at FROM reports"

    private fun ResultSet.toReport() = Report(
        id = getObject("id", UUID::class.java),
        reporterId = getObject("reporter_id", UUID::class.java),
        targetType = ReportTargetType.valueOf(getString("target_type")),
        targetId = getObject("target_id", UUID::class.java),
        reason = ReportReason.valueOf(getString("reason")),
        details = getString("details"),
        status = ReportStatus.valueOf(getString("status")),
        createdAt = getTimestamp("created_at").toInstant(),
        updatedAt = getTimestamp("updated_at").toInstant(),
    )
}
