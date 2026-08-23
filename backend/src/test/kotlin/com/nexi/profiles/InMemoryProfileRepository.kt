package com.nexi.profiles

import java.time.Instant
import java.util.UUID

internal class InMemoryProfileRepository(private val baseTime: Instant) : ProfileRepository {
    private data class Row(val id: UUID, val fullName: String, val username: String, val createdAt: Instant)

    private val users = mutableMapOf<UUID, Row>()
    private val follows = linkedMapOf<Pair<UUID, UUID>, Instant>()
    private var sequence = 0L

    var postCounts = mutableMapOf<UUID, Long>()

    fun addUser(fullName: String, username: String): UUID {
        val id = UUID.randomUUID()
        users[id] = Row(id, fullName, username, baseTime)
        return id
    }

    override fun findByUsername(username: String, viewerId: UUID): UserProfile? {
        val row = users.values.firstOrNull { it.username.equals(username, ignoreCase = true) } ?: return null
        return UserProfile(
            id = row.id,
            fullName = row.fullName,
            username = row.username,
            createdAt = row.createdAt,
            postCount = postCounts[row.id] ?: 0,
            followerCount = followerCount(row.id),
            followingCount = followingCount(row.id),
            followedByViewer = viewerId to row.id in follows,
            isViewer = row.id == viewerId,
        )
    }

    override fun findIdByUsername(username: String): UUID? =
        users.values.firstOrNull { it.username.equals(username, ignoreCase = true) }?.id

    override fun setFollow(followerId: UUID, followeeId: UUID, active: Boolean, now: Instant): Long {
        val key = followerId to followeeId
        if (active) {
            // Idempotent: zaten varsa zaman damgasını değiştirme.
            follows.putIfAbsent(key, baseTime.plusSeconds(sequence++))
        } else {
            follows.remove(key)
        }
        return followerCount(followeeId)
    }

    override fun followers(userId: UUID, viewerId: UUID, cursor: FollowCursor?, limit: Int): List<FollowEdge> =
        edges(follows.filterKeys { it.second == userId }.mapKeys { it.key.first }, viewerId, cursor, limit)

    override fun following(userId: UUID, viewerId: UUID, cursor: FollowCursor?, limit: Int): List<FollowEdge> =
        edges(follows.filterKeys { it.first == userId }.mapKeys { it.key.second }, viewerId, cursor, limit)

    override fun followerCount(userId: UUID): Long = follows.keys.count { it.second == userId }.toLong()

    override fun followingCount(userId: UUID): Long = follows.keys.count { it.first == userId }.toLong()

    private fun edges(
        selected: Map<UUID, Instant>,
        viewerId: UUID,
        cursor: FollowCursor?,
        limit: Int,
    ): List<FollowEdge> = selected.entries
        .mapNotNull { (otherId, createdAt) ->
            users[otherId]?.let { row ->
                FollowEdge(
                    userId = row.id,
                    fullName = row.fullName,
                    username = row.username,
                    followedByViewer = viewerId to row.id in follows,
                    createdAt = createdAt,
                )
            }
        }
        .sortedWith(compareByDescending<FollowEdge> { it.createdAt }.thenByDescending { it.userId })
        .filter {
            cursor == null ||
                it.createdAt < cursor.createdAt ||
                (it.createdAt == cursor.createdAt && it.userId < cursor.userId)
        }
        .take(limit)
}
