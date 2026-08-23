package com.nexi.stories

import com.nexi.media.MediaAsset
import com.nexi.media.MediaProcessingStatus
import com.nexi.media.MediaStatus
import com.nexi.posts.PostAuthorResponse
import java.time.Instant
import java.util.UUID

internal class InMemoryStoryRepository(private val baseTime: Instant) : StoryRepository {
    private data class UserRow(val id: UUID, val fullName: String, val username: String)

    private val users = mutableMapOf<UUID, UserRow>()
    private val stories = linkedMapOf<UUID, Story>()
    private val views = linkedMapOf<Pair<UUID, UUID>, Instant>()
    private val media = mutableMapOf<UUID, MediaAsset>()

    /** (takip eden, takip edilen) */
    val follows = mutableSetOf<Pair<UUID, UUID>>()

    /** Cift yonlu engeller. */
    val blocks = mutableSetOf<Pair<UUID, UUID>>()

    /** Zaten baska bir gonderide kullanilan medyalar. */
    val mediaInPosts = mutableSetOf<UUID>()

    private var sequence = 0L

    fun addUser(fullName: String, username: String): UUID {
        val id = UUID.randomUUID()
        users[id] = UserRow(id, fullName, username)
        return id
    }

    fun addMedia(
        ownerId: UUID,
        status: MediaStatus = MediaStatus.READY,
        processing: MediaProcessingStatus = MediaProcessingStatus.READY,
        mimeType: String = "image/png",
    ): UUID {
        val id = UUID.randomUUID()
        media[id] = MediaAsset(
            id = id,
            ownerId = ownerId,
            storageKey = "users/$ownerId/media/$id.png",
            originalFilename = "x.png",
            mimeType = mimeType,
            declaredSizeBytes = 10,
            actualSizeBytes = 10,
            status = status,
            createdAt = baseTime,
            updatedAt = baseTime,
            processingStatus = processing,
        )
        return id
    }

    fun storageKeyOf(mediaId: UUID): String = media.getValue(mediaId).storageKey

    fun mediaStatus(mediaId: UUID): MediaStatus = media.getValue(mediaId).status

    private fun blocked(a: UUID, b: UUID) = (a to b) in blocks || (b to a) in blocks

    override fun checkMedia(mediaId: UUID, ownerId: UUID): StoryMediaCheck {
        val asset = media[mediaId] ?: return StoryMediaCheck.NOT_FOUND
        return when {
            asset.ownerId != ownerId -> StoryMediaCheck.NOT_OWNED
            asset.status != MediaStatus.READY -> StoryMediaCheck.NOT_READY
            asset.processingStatus != MediaProcessingStatus.READY -> StoryMediaCheck.NOT_READY
            mediaId in mediaInPosts -> StoryMediaCheck.ALREADY_USED
            stories.values.any { it.mediaId == mediaId } -> StoryMediaCheck.ALREADY_USED
            else -> StoryMediaCheck.OK
        }
    }

    override fun create(story: Story): StoryDetails? {
        // Her yeni hikaye bir saniye sonraya dussun ki siralama belirli olsun.
        val stored = story.copy(publishedAt = baseTime.plusSeconds(sequence++))
        stories[stored.id] = stored
        return details(stored, stored.ownerId)
    }

    override fun feed(viewerId: UUID, now: Instant, limit: Int): List<StoryDetails> = stories.values
        .filter { it.status == StoryStatus.PUBLISHED && it.expiresAt > now }
        .filter { it.ownerId == viewerId || (viewerId to it.ownerId) in follows }
        .filterNot { blocked(viewerId, it.ownerId) }
        .sortedWith(compareBy<Story> { it.ownerId }.thenBy { it.publishedAt })
        .take(limit)
        .map { details(it, viewerId) }

    override fun byOwner(username: String, viewerId: UUID, now: Instant): List<StoryDetails>? {
        val owner = users.values.firstOrNull { it.username.equals(username, ignoreCase = true) } ?: return null
        if (blocked(viewerId, owner.id)) return null
        return stories.values
            .filter { it.ownerId == owner.id && it.status == StoryStatus.PUBLISHED && it.expiresAt > now }
            .sortedBy { it.publishedAt }
            .map { details(it, viewerId) }
    }

    override fun findDetails(storyId: UUID, viewerId: UUID, now: Instant): StoryDetails? {
        val story = stories[storyId]?.takeIf { it.status == StoryStatus.PUBLISHED && it.expiresAt > now } ?: return null
        if (blocked(viewerId, story.ownerId)) return null
        return details(story, viewerId)
    }

    override fun markViewed(storyId: UUID, viewerId: UUID, now: Instant): Boolean =
        views.putIfAbsent(storyId to viewerId, now) == null

    override fun viewers(storyId: UUID, cursor: StoryViewCursor?, limit: Int): List<StoryViewer> = views
        .filterKeys { it.first == storyId }
        .mapNotNull { (key, viewedAt) ->
            users[key.second]?.let { StoryViewer(it.id, it.fullName, it.username, null, viewedAt) }
        }
        .sortedWith(compareByDescending<StoryViewer> { it.viewedAt }.thenByDescending { it.userId })
        .filter {
            cursor == null ||
                it.viewedAt < cursor.viewedAt ||
                (it.viewedAt == cursor.viewedAt && it.userId < cursor.viewerId)
        }
        .take(limit)

    override fun viewerCount(storyId: UUID): Long = views.keys.count { it.first == storyId }.toLong()

    override fun markDeleted(storyId: UUID, ownerId: UUID, now: Instant): String? {
        val story = stories[storyId] ?: return null
        if (story.ownerId != ownerId || story.status != StoryStatus.PUBLISHED) return null
        release(story, now)
        return media.getValue(story.mediaId).storageKey
    }

    override fun expireOlderThan(now: Instant, limit: Int): List<Pair<UUID, String>> = stories.values
        .filter { it.status == StoryStatus.PUBLISHED && it.expiresAt <= now }
        .sortedBy { it.expiresAt }
        .take(limit)
        .map { story ->
            val key = media.getValue(story.mediaId).storageKey
            release(story, now)
            story.id to key
        }

    private fun release(story: Story, now: Instant) {
        stories[story.id] = story.copy(status = StoryStatus.DELETED)
        media[story.mediaId] = media.getValue(story.mediaId).copy(status = MediaStatus.DELETED, updatedAt = now)
    }

    private fun details(story: Story, viewerId: UUID): StoryDetails {
        val owner = users[story.ownerId]
        return StoryDetails(
            story = story,
            author = PostAuthorResponse(
                id = story.ownerId.toString(),
                fullName = owner?.fullName ?: "Bilinmeyen",
                username = owner?.username ?: "bilinmeyen",
                followedByMe = (viewerId to story.ownerId) in follows,
            ),
            media = media.getValue(story.mediaId),
            thumbnailStorageKey = null,
            viewCount = views.keys.count { it.first == story.id }.toLong(),
            seenByViewer = (story.id to viewerId) in views,
        )
    }
}
