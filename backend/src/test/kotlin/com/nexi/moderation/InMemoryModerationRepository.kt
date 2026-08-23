package com.nexi.moderation

import java.time.Instant
import java.util.UUID

internal class InMemoryModerationRepository(private val baseTime: Instant) : ModerationRepository {
    private data class Row(val id: UUID, val fullName: String, val username: String, val avatarKey: String? = null)

    private val users = mutableMapOf<UUID, Row>()
    private val blocks = linkedMapOf<Pair<UUID, UUID>, Instant>()
    private val reports = linkedMapOf<UUID, Report>()

    /** (takip eden, takip edilen) — engelleme bunu temizliyor mu, testler bakıyor. */
    val follows = mutableSetOf<Pair<UUID, UUID>>()

    /** Var olan hedefler; `targetExists` bunlara bakıyor. */
    val existingTargets = mutableSetOf<Pair<ReportTargetType, UUID>>()

    private var sequence = 0L

    fun addUser(fullName: String, username: String): UUID {
        val id = UUID.randomUUID()
        users[id] = Row(id, fullName, username)
        existingTargets += ReportTargetType.USER to id
        return id
    }

    fun idByUsername(username: String): UUID? =
        users.values.firstOrNull { it.username.equals(username, ignoreCase = true) }?.id

    fun addTarget(type: ReportTargetType): UUID = UUID.randomUUID().also { existingTargets += type to it }

    override fun setBlock(blockerId: UUID, blockedId: UUID, active: Boolean, now: Instant) {
        val key = blockerId to blockedId
        if (active) {
            blocks.putIfAbsent(key, baseTime.plusSeconds(sequence++))
            // Gercek uygulama gibi: engelleme karsilikli takibi kaldiriyor.
            follows -= blockerId to blockedId
            follows -= blockedId to blockerId
        } else {
            blocks.remove(key)
        }
    }

    override fun isBlockedEitherWay(userId: UUID, otherId: UUID): Boolean =
        (userId to otherId) in blocks || (otherId to userId) in blocks

    override fun blockedUsers(blockerId: UUID, cursor: BlockCursor?, limit: Int): List<BlockedUser> = blocks
        .filterKeys { it.first == blockerId }
        .mapNotNull { (key, createdAt) ->
            users[key.second]?.let {
                BlockedUser(it.id, it.fullName, it.username, it.avatarKey, createdAt)
            }
        }
        .sortedWith(compareByDescending<BlockedUser> { it.createdAt }.thenByDescending { it.userId })
        .filter {
            cursor == null ||
                it.createdAt < cursor.createdAt ||
                (it.createdAt == cursor.createdAt && it.userId < cursor.userId)
        }
        .take(limit)

    override fun blockedCount(blockerId: UUID): Long = blocks.keys.count { it.first == blockerId }.toLong()

    override fun createReport(report: Report): Pair<Report, Boolean> {
        val existing = reports.values.firstOrNull {
            it.reporterId == report.reporterId &&
                it.targetType == report.targetType &&
                it.targetId == report.targetId
        }
        if (existing != null) return existing to true

        val stored = report.copy(createdAt = baseTime.plusSeconds(sequence++))
        reports[stored.id] = stored
        return stored to false
    }

    override fun reports(reporterId: UUID, cursor: Instant?, cursorId: UUID?, limit: Int): List<Report> = reports.values
        .filter { it.reporterId == reporterId }
        .sortedWith(compareByDescending<Report> { it.createdAt }.thenByDescending { it.id })
        .filter {
            cursor == null ||
                it.createdAt < cursor ||
                (it.createdAt == cursor && cursorId != null && it.id < cursorId)
        }
        .take(limit)

    override fun reportCount(reporterId: UUID): Long = reports.values.count { it.reporterId == reporterId }.toLong()

    override fun targetExists(type: ReportTargetType, targetId: UUID): Boolean =
        (type to targetId) in existingTargets
}
