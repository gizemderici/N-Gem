package com.nexi.stories

import com.nexi.auth.ApiException
import com.nexi.auth.validation
import com.nexi.media.AvatarUrls
import com.nexi.media.ObjectStorage
import com.nexi.posts.withAvatar
import io.ktor.http.HttpStatusCode
import java.nio.charset.StandardCharsets
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.UUID

class StoryService(
    private val repository: StoryRepository,
    private val storage: ObjectStorage,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val mediaUrlExpiry: Duration = Duration.ofMinutes(15)

    fun create(ownerId: UUID, request: CreateStoryRequest): StoryResponse {
        val mediaId = runCatching { UUID.fromString(request.mediaId) }.getOrElse {
            throw validation("INVALID_MEDIA_ID", "Medya kimliği geçersiz.", "mediaId")
        }

        val caption = request.caption?.trim()?.takeIf { it.isNotEmpty() }
        if (caption != null && caption.length > MAX_CAPTION_LENGTH) {
            throw validation("CAPTION_TOO_LONG", "Açıklama en fazla $MAX_CAPTION_LENGTH karakter olabilir.", "caption")
        }

        when (repository.checkMedia(mediaId, ownerId)) {
            StoryMediaCheck.OK -> Unit
            // Başkasının medyasının varlığını açığa çıkarmamak için "yok" ile aynı kod.
            StoryMediaCheck.NOT_FOUND, StoryMediaCheck.NOT_OWNED ->
                throw validation("MEDIA_NOT_AVAILABLE", "Medya bulunamadı veya sana ait değil.", "mediaId")
            StoryMediaCheck.NOT_READY ->
                throw validation("MEDIA_NOT_READY", "Medya henüz kullanıma hazır değil.", "mediaId")
            StoryMediaCheck.ALREADY_USED ->
                throw ApiException(
                    HttpStatusCode.Conflict,
                    "MEDIA_ALREADY_ATTACHED",
                    "Bu medya başka bir gönderide veya hikâyede kullanılıyor.",
                )
        }

        val now = clock.instant()
        val details = repository.create(
            Story(
                id = UUID.randomUUID(),
                ownerId = ownerId,
                mediaId = mediaId,
                caption = caption,
                status = StoryStatus.PUBLISHED,
                publishedAt = now,
                expiresAt = now.plus(LIFETIME),
            )
        ) ?: throw ApiException(HttpStatusCode.InternalServerError, "STORY_CREATION_FAILED", "Hikâye oluşturulamadı.")

        return details.toResponse(ownerId)
    }

    /** Takip edilenlerin ve kullanıcının kendi aktif hikâyeleri, yazara göre gruplu. */
    fun feed(viewerId: UUID): StoryFeedResponse {
        val groups = repository.feed(viewerId, clock.instant(), MAX_FEED_STORIES)
            .groupBy { it.story.ownerId }
            .map { (_, stories) ->
                StoryGroupResponse(
                    author = stories.first().author.withAvatar(storage),
                    stories = stories.map { it.toResponse(viewerId) },
                    hasUnseen = stories.any { !it.seenByViewer },
                    latestPublishedAt = stories.maxOf { it.story.publishedAt }.toString(),
                )
            }
            // Görülmemişi olanlar önce; sonra en yeni hikâye.
            .sortedWith(compareByDescending<StoryGroupResponse> { it.hasUnseen }.thenByDescending { it.latestPublishedAt })

        return StoryFeedResponse(groups)
    }

    fun byUsername(viewerId: UUID, username: String): StoryFeedResponse {
        val stories = repository.byOwner(username, viewerId, clock.instant())
            ?: throw ApiException(HttpStatusCode.NotFound, "USER_NOT_FOUND", "Kullanıcı bulunamadı.")
        if (stories.isEmpty()) return StoryFeedResponse(emptyList())

        return StoryFeedResponse(
            listOf(
                StoryGroupResponse(
                    author = stories.first().author.withAvatar(storage),
                    stories = stories.map { it.toResponse(viewerId) },
                    hasUnseen = stories.any { !it.seenByViewer },
                    latestPublishedAt = stories.maxOf { it.story.publishedAt }.toString(),
                )
            )
        )
    }

    /** Görüntülemeyi kaydeder. Kendi hikâyene bakmak görüntüleme sayılmaz. */
    fun markViewed(viewerId: UUID, storyId: UUID): StoryViewResponse {
        val details = repository.findDetails(storyId, viewerId, clock.instant()) ?: throw storyNotFound()
        if (details.story.ownerId == viewerId) {
            return StoryViewResponse(storyId.toString(), seen = true)
        }

        repository.markViewed(storyId, viewerId, clock.instant())
        return StoryViewResponse(storyId.toString(), seen = true)
    }

    /** Görüntüleyen listesini yalnızca hikâyenin sahibi görebilir. */
    fun viewers(viewerId: UUID, storyId: UUID, rawCursor: String?, requestedLimit: Int?): StoryViewerPageResponse {
        val details = repository.findDetails(storyId, viewerId, clock.instant()) ?: throw storyNotFound()
        // Yetkisiz kişiye 403 dönmek hikâyenin varlığını doğrulardı.
        if (details.story.ownerId != viewerId) throw storyNotFound()

        val limit = (requestedLimit ?: DEFAULT_PAGE_SIZE).coerceIn(1, MAX_PAGE_SIZE)
        val rows = repository.viewers(storyId, rawCursor?.let { decodeCursor(it) }, limit + 1)
        val hasMore = rows.size > limit
        val items = rows.take(limit)

        return StoryViewerPageResponse(
            items = items.map {
                val avatar = AvatarUrls.of(storage, it.avatarStorageKey)
                StoryViewerResponse(
                    id = it.userId.toString(),
                    fullName = it.fullName,
                    username = it.username,
                    avatarUrl = avatar?.url,
                    avatarUrlExpiresInSeconds = avatar?.expiresInSeconds,
                    viewedAt = it.viewedAt.toString(),
                )
            },
            nextCursor = if (hasMore) items.lastOrNull()?.let { encodeCursor(it) } else null,
            totalCount = repository.viewerCount(storyId),
        )
    }

    fun delete(ownerId: UUID, storyId: UUID) {
        val releasedKey = repository.markDeleted(storyId, ownerId, clock.instant()) ?: throw storyNotFound()
        // Veritabanı tutarlı; depo silme başarısız olursa isteği düşürmüyoruz.
        runCatching { storage.delete(releasedKey) }
    }

    private fun StoryDetails.toResponse(viewerId: UUID) = StoryResponse(
        id = story.id.toString(),
        author = author.withAvatar(storage),
        caption = story.caption,
        media = StoryMediaResponse(
            id = media.id.toString(),
            mimeType = media.mimeType,
            url = storage.createDownloadUrl(media.storageKey, mediaUrlExpiry),
            urlExpiresInSeconds = mediaUrlExpiry.seconds,
            durationSeconds = media.durationSeconds,
            width = media.width,
            height = media.height,
            thumbnailUrl = thumbnailStorageKey?.let { storage.createDownloadUrl(it, mediaUrlExpiry) },
        ),
        publishedAt = story.publishedAt.toString(),
        expiresAt = story.expiresAt.toString(),
        seenByMe = seenByViewer,
        // Sayaç yalnızca sahibine; başkası kaç kişinin baktığını görmemeli.
        viewCount = viewCount.takeIf { story.ownerId == viewerId },
    )

    private fun storyNotFound() =
        ApiException(HttpStatusCode.NotFound, "STORY_NOT_FOUND", "Hikâye bulunamadı.")

    companion object {
        /** Hikâyeler 24 saat sonra sona erer. */
        val LIFETIME: Duration = Duration.ofHours(24)
        const val MAX_CAPTION_LENGTH = 280
        const val DEFAULT_PAGE_SIZE = 20
        const val MAX_PAGE_SIZE = 50

        /**
         * Hikâye şeridi sayfalanmıyor: içerik 24 saatte yok oluyor ve takip
         * grafiği sınırlı. Yine de kaçak bir durumda sorgunun büyümemesi için tavan.
         */
        const val MAX_FEED_STORIES = 500

        internal fun encodeCursor(viewer: StoryViewer): String {
            val raw = "${viewer.viewedAt.toEpochMilli()}|${viewer.userId}"
            return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.toByteArray(StandardCharsets.UTF_8))
        }

        internal fun decodeCursor(rawCursor: String): StoryViewCursor = try {
            val decoded = String(Base64.getUrlDecoder().decode(rawCursor), StandardCharsets.UTF_8)
            val parts = decoded.split('|', limit = 2)
            StoryViewCursor(Instant.ofEpochMilli(parts[0].toLong()), UUID.fromString(parts[1]))
        } catch (_: Throwable) {
            throw ApiException(HttpStatusCode.BadRequest, "INVALID_CURSOR", "Liste imleci geçersiz.", "cursor")
        }
    }
}
