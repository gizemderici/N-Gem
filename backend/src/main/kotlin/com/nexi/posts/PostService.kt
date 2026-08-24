package com.nexi.posts

import com.nexi.auth.ApiException
import com.nexi.auth.validation
import com.nexi.media.ObjectStorage
import com.nexi.notifications.NotificationSink
import com.nexi.notifications.NotificationTargetType
import com.nexi.notifications.NotificationType
import com.nexi.recommendations.EmptyRecommendationRepository
import com.nexi.recommendations.PersonalizationConsent
import com.nexi.recommendations.RecommendationEventType
import com.nexi.recommendations.RecommendationRepository
import com.nexi.recommendations.serverEvent
import io.ktor.http.HttpStatusCode
import org.slf4j.LoggerFactory
import java.nio.charset.StandardCharsets
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.UUID

class PostService(
    private val repository: PostRepository,
    private val storage: ObjectStorage,
    private val clock: Clock = Clock.systemUTC(),
    private val recommendationRepository: RecommendationRepository = EmptyRecommendationRepository,
    private val notifications: NotificationSink = NotificationSink.NOOP,
    private val consent: PersonalizationConsent = PersonalizationConsent.ALWAYS_GRANTED,
) {
    private val logger = LoggerFactory.getLogger(PostService::class.java)
    private val mediaUrlExpiry = Duration.ofMinutes(15)

    fun create(ownerId: UUID, request: CreatePostRequest): PostResponse {
        val body = request.text.trim()
        if (body.length > 2_000) throw validation("POST_TOO_LONG", "Gönderi metni en fazla 2000 karakter olabilir.", "text")
        if (request.mediaIds.size > 4) throw validation("TOO_MANY_MEDIA", "Bir gönderiye en fazla dört görsel eklenebilir.", "mediaIds")
        if (request.topicIds.size > MAX_POST_TOPICS) {
            throw validation("TOO_MANY_TOPICS", "Bir gönderiye en fazla $MAX_POST_TOPICS konu eklenebilir.", "topicIds")
        }

        val mediaIds = request.mediaIds.map { rawId ->
            runCatching { UUID.fromString(rawId) }.getOrElse {
                throw validation("INVALID_MEDIA_ID", "Medya kimliği geçersiz.", "mediaIds")
            }
        }
        if (mediaIds.distinct().size != mediaIds.size) {
            throw validation("DUPLICATE_MEDIA", "Aynı görsel bir gönderiye birden fazla eklenemez.", "mediaIds")
        }
        val topicIds = request.topicIds.map { rawId ->
            runCatching { UUID.fromString(rawId) }.getOrElse {
                throw validation("INVALID_TOPIC_ID", "Konu kimliği geçersiz.", "topicIds")
            }
        }
        if (topicIds.distinct().size != topicIds.size) {
            throw validation("DUPLICATE_TOPIC", "Aynı konu bir gönderiye birden fazla eklenemez.", "topicIds")
        }
        if (body.isBlank() && mediaIds.isEmpty()) {
            throw validation("EMPTY_POST", "Gönderi metni veya en az bir görsel gerekli.")
        }

        val post = try {
            repository.create(ownerId, body, mediaIds, topicIds, clock.instant())
        } catch (_: MediaOwnershipException) {
            throw validation("MEDIA_NOT_AVAILABLE", "Medya dosyalarından biri hazır değil veya sana ait değil.", "mediaIds")
        } catch (_: MediaAlreadyAttachedException) {
            throw ApiException(HttpStatusCode.Conflict, "MEDIA_ALREADY_ATTACHED", "Medya dosyalarından biri başka bir gönderide kullanılıyor.")
        } catch (_: UnknownTopicException) {
            throw validation("UNKNOWN_TOPIC", "Seçilen konulardan biri kullanılamıyor.", "topicIds")
        }
        val details = repository.findDetails(post.id, ownerId)
            ?: throw ApiException(HttpStatusCode.InternalServerError, "POST_CREATION_FAILED", "Gönderi oluşturulamadı.")
        return response(details)
    }

    fun get(viewerId: UUID, postId: UUID): PostResponse = response(
        repository.findDetails(postId, viewerId)
            ?: throw ApiException(HttpStatusCode.NotFound, "POST_NOT_FOUND", "Gönderi bulunamadı.")
    )

    /** Profil ekranının listesi: kullanıcının kendi gönderileri, kronolojik. */
    fun postsByOwner(ownerId: UUID, viewerId: UUID, rawCursor: String?, requestedLimit: Int?): FeedResponse =
        page(
            rawCursor?.let { decodeCursor(it) },
            (requestedLimit ?: DEFAULT_PAGE_SIZE).coerceIn(1, MAX_PAGE_SIZE),
        ) { cursor, size -> repository.postsByOwner(ownerId, viewerId, cursor, size) }

    /**
     * Kronolojik sayfalamanın ortak kısmı: bir fazla kayıt çekip devamı olup
     * olmadığını anlıyor, imleci son öğeden üretiyor.
     */
    private fun page(
        cursor: FeedCursor?,
        limit: Int,
        load: (FeedCursor?, Int) -> List<PostDetails>,
    ): FeedResponse {
        val details = load(cursor, limit + 1)
        val hasMore = details.size > limit
        val items = details.take(limit)
        val nextCursor = if (hasMore) items.lastOrNull()?.post?.let { encodeCursor(it) } else null
        return FeedResponse(items.map(::response), nextCursor)
    }

    fun delete(ownerId: UUID, postId: UUID) {
        val releasedKeys = repository.markDeleted(postId, ownerId, clock.instant())
            ?: throw ApiException(HttpStatusCode.NotFound, "POST_NOT_FOUND", "Gönderi bulunamadı.")

        // Veritabanı zaten tutarlı; depodan silme başarısız olursa isteği
        // düşürmüyoruz. Arta kalan dosyayı MediaJanitor'ın süpürmesi yakalar.
        releasedKeys.forEach { key ->
            runCatching { storage.delete(key) }.onFailure {
                logger.warn("Could not remove stored object for deleted post: key={}", key, it)
            }
        }
    }

    fun setLike(userId: UUID, postId: UUID, active: Boolean): PostInteractionResponse {
        val details = repository.findDetails(postId, userId)
            ?: throw ApiException(HttpStatusCode.NotFound, "POST_NOT_FOUND", "Gönderi bulunamadı.")
        val now = clock.instant()
        val count = repository.setLike(postId, userId, active, now)
        // Begeniyi geri almak bildirim uretmez.
        if (active) {
            notifications.emit(
                details.post.ownerId, userId,
                NotificationType.POST_LIKE, NotificationTargetType.POST, postId,
            )
        }
        recordInteraction(userId, postId, RecommendationEventType.CONTENT_LIKED, active, details.likedByViewer, now)
        return PostInteractionResponse(postId.toString(), active, count)
    }

    fun setSave(userId: UUID, postId: UUID, active: Boolean): PostInteractionResponse {
        val details = repository.findDetails(postId, userId)
            ?: throw ApiException(HttpStatusCode.NotFound, "POST_NOT_FOUND", "Gönderi bulunamadı.")
        val now = clock.instant()
        val count = repository.setSave(postId, userId, active, now)
        recordInteraction(userId, postId, RecommendationEventType.CONTENT_SAVED, active, details.savedByViewer, now)
        return PostInteractionResponse(postId.toString(), active, count)
    }

    /**
     * Etkileşim sinyalini istemciden beklemeden burada yazıyoruz: beğeni zaten
     * sunucuda işleniyor, olayı da burada üretmek uygulama kapansa bile
     * kaybolmamasını sağlıyor.
     *
     * Yalnızca gerçek geçişte yazılır. Aksi hâlde arayüzün tekrar gönderdiği
     * istek ya da çift dokunuş aynı beğeniyi birden çok kez öğretirdi.
     */
    private fun recordInteraction(
        userId: UUID,
        postId: UUID,
        type: RecommendationEventType,
        active: Boolean,
        alreadyActive: Boolean,
        now: Instant,
    ) {
        if (!active || alreadyActive) return
        // Riza vermemis kullanicinin begenisi AI verisi uretmemeli. Kontrol
        // RecommendationService icinde vardi ama sunucu uretimli sinyaller o
        // kapiyi atliyordu.
        if (!consent.isGranted(userId)) return
        runCatching {
            recommendationRepository.append(listOf(serverEvent(userId, postId, type, "post", now)))
        }.onFailure {
            // Sinyal kaybı etkileşimi geri almayı hak etmez; beğeni zaten yazıldı.
            logger.warn("Could not record interaction signal: post={} type={}", postId, type, it)
        }
    }

    private fun ensureVisible(postId: UUID, viewerId: UUID) {
        if (repository.findDetails(postId, viewerId) == null) {
            throw ApiException(HttpStatusCode.NotFound, "POST_NOT_FOUND", "Gönderi bulunamadı.")
        }
    }

    private fun response(details: PostDetails, recommendationReason: String? = null): PostResponse =
        details.toResponse(storage, mediaUrlExpiry)
            .copy(recommendationReason = recommendationReason)

    companion object {
        const val MAX_POST_TOPICS = 3
        const val DEFAULT_PAGE_SIZE = 20
        const val MAX_PAGE_SIZE = 50

        internal fun encodeCursor(post: Post): String {
            val raw = "${post.createdAt.toEpochMilli()}|${post.id}"
            return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.toByteArray(StandardCharsets.UTF_8))
        }

        internal fun decodeCursor(rawCursor: String): FeedCursor = try {
            val decoded = String(Base64.getUrlDecoder().decode(rawCursor), StandardCharsets.UTF_8)
            val parts = decoded.split('|', limit = 2)
            FeedCursor(Instant.ofEpochMilli(parts[0].toLong()), UUID.fromString(parts[1]))
        } catch (_: Throwable) {
            throw ApiException(HttpStatusCode.BadRequest, "INVALID_CURSOR", "Akış imleci geçersiz.", "cursor")
        }
    }
}
