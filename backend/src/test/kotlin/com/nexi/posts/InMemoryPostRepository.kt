package com.nexi.posts

import com.nexi.media.MediaAsset
import com.nexi.media.MediaStatus
import com.nexi.media.ObjectStorage
import com.nexi.media.StoredObjectInfo
import com.nexi.topics.InMemoryTopicRepository
import com.nexi.topics.Topic
import java.time.Duration
import java.time.Instant
import java.util.UUID

internal class InMemoryPostRepository(
    private val authorId: UUID,
    private val baseTime: Instant,
    private val topicRepository: InMemoryTopicRepository = InMemoryTopicRepository(),
) : PostRepository {
    private val posts = linkedMapOf<UUID, Post>()
    private val media = mutableMapOf<UUID, MediaAsset>()
    private val attachedMedia = mutableSetOf<UUID>()
    private val postMedia = mutableMapOf<UUID, List<UUID>>()
    private val postTopics = mutableMapOf<UUID, List<UUID>>()
    private val likes = mutableSetOf<Pair<UUID, UUID>>()
    private val saves = mutableSetOf<Pair<UUID, UUID>>()

    /** (takip eden, takip edilen) — akış katmanı testleri için. */
    val follows = mutableSetOf<Pair<UUID, UUID>>()
    private val knownTopicIds = topicRepository.catalogTopics.map { it.id }.toSet()
    private var sequence = 0L

    fun addReadyMedia(mediaOwnerId: UUID): MediaAsset {
        val id = UUID.randomUUID()
        return MediaAsset(
            id, mediaOwnerId, "users/$mediaOwnerId/media/$id.png", "test.png", "image/png",
            8, 8, MediaStatus.READY, baseTime, baseTime,
        ).also { media[id] = it }
    }

    override fun create(ownerId: UUID, body: String, mediaIds: List<UUID>, topicIds: List<UUID>, now: Instant): Post {
        mediaIds.forEach { id ->
            val asset = media[id]
            if (asset == null || asset.ownerId != ownerId || asset.status != MediaStatus.READY) throw MediaOwnershipException()
            if (id in attachedMedia) throw MediaAlreadyAttachedException()
        }
        if (topicIds.any { it !in knownTopicIds }) throw UnknownTopicException()

        val timestamp = now.minusSeconds(sequence++)
        val post = Post(UUID.randomUUID(), ownerId, body, PostStatus.PUBLISHED, timestamp, timestamp)
        posts[post.id] = post
        attachedMedia += mediaIds
        postMedia[post.id] = mediaIds
        postTopics[post.id] = topicIds
        return post
    }

    override fun findDetails(postId: UUID, viewerId: UUID): PostDetails? {
        val post = posts[postId]?.takeIf { it.status == PostStatus.PUBLISHED } ?: return null
        return hydrate(listOf(details(post, viewerId))).single()
    }

    override fun feed(viewerId: UUID, cursor: FeedCursor?, limit: Int): List<PostDetails> =
        hydrate(published().afterCursor(cursor).take(limit).map { details(it, viewerId) })

    override fun postsByOwner(ownerId: UUID, viewerId: UUID, cursor: FeedCursor?, limit: Int): List<PostDetails> =
        hydrate(
            published()
                .filter { it.ownerId == ownerId }
                .afterCursor(cursor)
                .take(limit)
                .map { details(it, viewerId) }
        )

    override fun feedTier(
        viewerId: UUID,
        tier: FeedTier,
        priorityTopicCount: Int,
        cursor: FeedCursor?,
        limit: Int,
    ): List<PostDetails> {
        val positions = topicRepository.userTopics(viewerId).associate { it.topic.id to it.position }
        val relatedIds = topicRepository.relationsFor(positions.keys.toList()).map { it.relatedTopicId }.toSet()

        return published()
            .filter {
                tierOf(
                    topicIds = postTopics[it.id].orEmpty(),
                    positions = positions,
                    relatedIds = relatedIds,
                    priorityTopicCount = priorityTopicCount,
                    followsAuthor = viewerId to it.ownerId in follows,
                ) == tier
            }
            .afterCursor(cursor)
            .take(limit)
            .map { details(it, viewerId) }
    }

    override fun hydrate(details: List<PostDetails>): List<PostDetails> = details.map { item ->
        item.copy(
            media = postMedia[item.post.id].orEmpty().mapNotNull(media::get),
            topics = postTopics[item.post.id].orEmpty().mapNotNull { id -> topicOf(id) },
        )
    }

    override fun markDeleted(postId: UUID, ownerId: UUID, now: Instant): List<String>? {
        val post = posts[postId] ?: return null
        if (post.ownerId != ownerId || post.status != PostStatus.PUBLISHED) return null
        posts[postId] = post.copy(status = PostStatus.DELETED, updatedAt = now)

        val releasedIds = postMedia.remove(postId).orEmpty()
        attachedMedia -= releasedIds.toSet()
        return releasedIds.mapNotNull { id ->
            media[id]?.also { media[id] = it.copy(status = MediaStatus.DELETED, updatedAt = now) }?.storageKey
        }
    }

    override fun setLike(postId: UUID, userId: UUID, active: Boolean, now: Instant): Long {
        if (active) likes += postId to userId else likes -= postId to userId
        return likes.count { it.first == postId }.toLong()
    }

    override fun setSave(postId: UUID, userId: UUID, active: Boolean, now: Instant): Long {
        if (active) saves += postId to userId else saves -= postId to userId
        return saves.count { it.first == postId }.toLong()
    }

    /**
     * Bellek içi arama: gerçek tam metin yerine basit içerme kontrolü.
     * Türkçe normalizasyonu kabaca taklit ediyor ki testler aynı davranışı görsün.
     */
    override fun search(viewerId: UUID, query: String, cursor: RankedPostCursor?, limit: Int): List<RankedPost> {
        val needle = normalizeText(query)
        return published()
            .filter { normalizeText(it.body).contains(needle) }
            .map { RankedPost(details(it, viewerId), 1.0) }
            .afterRankedCursor(cursor)
            .take(limit)
    }

    override fun explore(viewerId: UUID, rankedAt: Instant, cursor: RankedPostCursor?, limit: Int): List<RankedPost> = published()
        .filter { it.createdAt <= rankedAt }
        .map { post ->
            val likeCount = likes.count { it.first == post.id }
            val ageSeconds = java.time.Duration.between(post.createdAt, rankedAt).seconds.coerceAtLeast(0)
            val rank = kotlin.math.ln(1.0 + likeCount) *
                kotlin.math.exp(-ageSeconds / EXPLORE_HALF_LIFE_SECONDS)
            RankedPost(details(post, viewerId), rank)
        }
        .afterRankedCursor(cursor)
        .take(limit)

    private fun List<RankedPost>.afterRankedCursor(cursor: RankedPostCursor?): List<RankedPost> = this
        .sortedWith(
            compareByDescending<RankedPost> { it.rank }
                .thenByDescending { it.details.post.createdAt }
                .thenByDescending { it.details.post.id }
        )
        .filter { item ->
            val post = item.details.post
            cursor == null ||
                item.rank < cursor.rank ||
                (item.rank == cursor.rank && post.createdAt < cursor.createdAt) ||
                (item.rank == cursor.rank && post.createdAt == cursor.createdAt && post.id < cursor.id)
        }

    /** Aksanları düzleyip küçük harfe indiriyor; `unaccent` + `lower` karşılığı. */
    private fun normalizeText(text: String) = text.lowercase()
        .replace(Regex("[ıİ]"), "i")
        .replace("ş", "s").replace("ğ", "g")
        .replace("ü", "u").replace("ö", "o").replace("ç", "c")

    /** JdbcPostRepository'deki dışlayıcı katman koşullarının bellek içi karşılığı. */
    private fun tierOf(
        topicIds: List<UUID>,
        positions: Map<UUID, Int>,
        relatedIds: Set<UUID>,
        priorityTopicCount: Int,
        followsAuthor: Boolean,
    ): FeedTier {
        val selectedPositions = topicIds.mapNotNull { positions[it] }
        return when {
            followsAuthor -> FeedTier.FOLLOWING
            selectedPositions.any { it < priorityTopicCount } -> FeedTier.PRIORITY_TOPIC
            selectedPositions.isNotEmpty() -> FeedTier.OTHER_TOPIC
            topicIds.any { it in relatedIds } -> FeedTier.RELATED_TOPIC
            else -> FeedTier.DISCOVERY
        }
    }

    private fun published(): List<Post> = posts.values
        .filter { it.status == PostStatus.PUBLISHED }
        .sortedWith(compareByDescending<Post> { it.createdAt }.thenByDescending { it.id })

    private fun List<Post>.afterCursor(cursor: FeedCursor?): List<Post> = filter {
        cursor == null || it.createdAt < cursor.createdAt || (it.createdAt == cursor.createdAt && it.id < cursor.id)
    }

    private fun topicOf(id: UUID): Topic? = topicRepository.catalogTopics.firstOrNull { it.id == id }

    /** Yazar adı; kayıtlı değilse varsayılan. Takip testleri farklı yazarlara ihtiyaç duyuyor. */
    val authorNames = mutableMapOf<UUID, String>()

    private fun details(post: Post, viewerId: UUID) = PostDetails(
        post = post,
        author = PostAuthorResponse(
            id = post.ownerId.toString(),
            fullName = authorNames[post.ownerId] ?: "Gizem Derici",
            username = if (post.ownerId == authorId) "gizem" else "kullanici",
            followedByMe = viewerId to post.ownerId in follows,
        ),
        media = emptyList(),
        topics = emptyList(),
        likeCount = likes.count { it.first == post.id }.toLong(),
        saveCount = saves.count { it.first == post.id }.toLong(),
        // Yorum sayacı gerçek uygulamada SQL alt sorgusundan geliyor; burada
        // gönderi testlerini ilgilendirmediği için sabit sıfır.
        commentCount = 0,
        likedByViewer = post.id to viewerId in likes,
        savedByViewer = post.id to viewerId in saves,
    )
}

internal class FakeObjectStorage : ObjectStorage {
    val deletedKeys = mutableSetOf<String>()
    override fun createUploadUrl(key: String, mimeType: String, expiresIn: Duration) = error("unused")
    override fun inspect(key: String): StoredObjectInfo = error("unused")
    override fun createDownloadUrl(key: String, expiresIn: Duration) = "http://storage/$key"
    override fun readRange(key: String, start: Long, endInclusive: Long) = ByteArray(0)
    override fun delete(key: String) { deletedKeys += key }
}
