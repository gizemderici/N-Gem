package com.nexi.profiles

import java.time.Instant
import java.util.UUID

internal class InMemoryProfileRepository(private val baseTime: Instant) : ProfileRepository {
    private data class Row(
        val id: UUID,
        val fullName: String,
        val username: String,
        val createdAt: Instant,
        val bio: String? = null,
        val avatarMediaId: UUID? = null,
    )

    /** mediaId -> (sahibi, durum, mimeType, depolama anahtari) */
    val media = mutableMapOf<UUID, MediaRow>()

    data class MediaRow(val ownerId: UUID, val status: String, val mimeType: String, val storageKey: String)

    fun addMedia(ownerId: UUID, status: String = "READY", mimeType: String = "image/png"): UUID {
        val id = UUID.randomUUID()
        media[id] = MediaRow(ownerId, status, mimeType, "users/$ownerId/media/$id.png")
        return id
    }

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
            bio = row.bio,
            // Gercek sorgu gibi: yalnizca READY medya avatar olarak gorunur.
            avatarStorageKey = row.avatarMediaId
                ?.let { media[it] }
                ?.takeIf { it.status == "READY" }
                ?.storageKey,
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

    override fun findUsernameById(userId: UUID): String? = users[userId]?.username

    override fun updateProfile(userId: UUID, fullName: String?, bio: String?, clearBio: Boolean, now: Instant) {
        val row = users[userId] ?: return
        users[userId] = row.copy(
            fullName = fullName ?: row.fullName,
            bio = if (clearBio) null else bio ?: row.bio,
        )
    }

    override fun checkAvatarMedia(mediaId: UUID, ownerId: UUID): AvatarMediaCheck {
        val asset = media[mediaId] ?: return AvatarMediaCheck.NOT_FOUND
        return when {
            asset.ownerId != ownerId -> AvatarMediaCheck.NOT_OWNED
            asset.status != "READY" -> AvatarMediaCheck.NOT_READY
            !asset.mimeType.startsWith("image/") -> AvatarMediaCheck.NOT_AN_IMAGE
            else -> AvatarMediaCheck.OK
        }
    }

    override fun setAvatar(userId: UUID, mediaId: UUID?, now: Instant) {
        val row = users[userId] ?: return
        users[userId] = row.copy(avatarMediaId = mediaId)
    }

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
                    avatarStorageKey = row.avatarMediaId
                        ?.let { media[it] }
                        ?.takeIf { it.status == "READY" }
                        ?.storageKey,
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
