package com.nexi.feed

import com.nexi.auth.ApiException
import com.nexi.media.ObjectStorage
import com.nexi.posts.FeedCursor
import com.nexi.posts.FeedTier
import com.nexi.posts.PostDetails
import com.nexi.posts.PostRepository
import com.nexi.posts.toResponse
import com.nexi.topics.Topic
import com.nexi.topics.TopicService
import com.nexi.topics.UserTopic
import com.nexi.topics.TopicRepository
import com.nexi.topics.toSummary
import io.ktor.http.HttpStatusCode
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.UUID

/**
 * Başlangıç akışı: kullanıcının kendi sıraladığı ilgi alanlarından yaklaşık %70,
 * bu alanlarla ilişkili konulardan %20, keşiften %10 içerik getirir.
 *
 * Oranlar sabit bir desenle (bkz. [PATTERN]) uygulanır; desen slotu imleçte
 * taşındığı için sayfa sınırlarında dağılım bozulmaz. Bir katman tükenirse boş
 * kalan slotlar [fallbackOrder] sırasına göre diğer katmanlardan doldurulur,
 * böylece içerik varken akış erken bitmez.
 *
 * Bu sürüm yalnızca kullanıcının açık tercihine bakar; davranış sinyalleri ve
 * öğrenilmiş ağırlıklar (Nexi Akışı) sonraki adımda bu servisin üzerine gelir.
 */
class FeedService(
    private val postRepository: PostRepository,
    private val topicRepository: TopicRepository,
    private val storage: ObjectStorage,
) {
    private val mediaUrlExpiry = Duration.ofMinutes(15)

    fun feed(viewerId: UUID, rawCursor: String?, requestedLimit: Int?): MixedFeedResponse {
        val limit = (requestedLimit ?: DEFAULT_LIMIT).coerceIn(1, MAX_LIMIT)
        val userTopics = topicRepository.userTopics(viewerId)
        if (userTopics.isEmpty()) return chronologicalFeed(viewerId, rawCursor, limit)

        val cursor = rawCursor?.let { decodeCursor(it) } ?: MixedFeedCursor(emptyMap(), 0)
        val buffers = FeedTier.entries.associateWith { tier ->
            ArrayDeque(
                postRepository.feedTier(
                    viewerId = viewerId,
                    tier = tier,
                    priorityTopicCount = TopicService.PRIORITY_TOPIC_COUNT,
                    cursor = cursor.tiers[tier]?.let { FeedCursor(it.createdAt, it.id) },
                    // Tek bir katman sayfanın tamamını doldurabileceği için her katmandan
                    // sayfa boyu kadar okuyoruz; medya ve konular burada yüklenmiyor.
                    limit = limit,
                )
            )
        }

        val picked = mutableListOf<Pair<FeedTier, PostDetails>>()
        var slot = cursor.slot
        while (picked.size < limit) {
            val wanted = PATTERN[Math.floorMod(slot, PATTERN.size)]
            val tier = fallbackOrder(wanted).firstOrNull { buffers.getValue(it).isNotEmpty() } ?: break
            picked += tier to buffers.getValue(tier).removeFirst()
            slot++
        }

        if (picked.isEmpty()) {
            return MixedFeedResponse(emptyList(), FeedMixResponse(0, 0, 0), personalized = true, nextCursor = null)
        }

        val hydrated = postRepository.hydrate(picked.map { it.second })
        val items = picked.mapIndexed { index, (tier, _) -> tier to hydrated[index] }
        val reasons = ReasonBuilder(userTopics, topicRepository)

        val nextTierCursors = buildMap {
            putAll(cursor.tiers)
            picked.forEach { (tier, details) -> put(tier, TierCursor(details.post.createdAt, details.post.id)) }
        }
        // Sayfa dolduysa devamı olabilir; dolmadıysa katmanların tamamı tükenmiştir.
        val nextCursor = if (picked.size == limit) encodeCursor(MixedFeedCursor(nextTierCursors, slot)) else null

        return MixedFeedResponse(
            items = items.map { (tier, details) ->
                FeedItemResponse(
                    post = details.toResponse(storage, mediaUrlExpiry),
                    source = tier.feedSource(),
                    reason = reasons.build(tier, details),
                )
            },
            mix = FeedMixResponse(
                primary = items.count { it.first.feedSource() == SOURCE_PRIMARY },
                related = items.count { it.first.feedSource() == SOURCE_RELATED },
                discovery = items.count { it.first.feedSource() == SOURCE_DISCOVERY },
                following = items.count { it.first.feedSource() == SOURCE_FOLLOWING },
            ),
            personalized = true,
            nextCursor = nextCursor,
        )
    }

    /** Henüz ilgi alanı seçmemiş kullanıcı için düz kronolojik akış. */
    private fun chronologicalFeed(viewerId: UUID, rawCursor: String?, limit: Int): MixedFeedResponse {
        val cursor = rawCursor?.let { decodeCursor(it) }?.tiers?.get(FeedTier.DISCOVERY)
        val details = postRepository.feed(
            viewerId,
            cursor?.let { FeedCursor(it.createdAt, it.id) },
            limit + 1,
        )
        val hasMore = details.size > limit
        val page = details.take(limit)
        val nextCursor = page.lastOrNull()
            ?.takeIf { hasMore }
            ?.let {
                encodeCursor(
                    MixedFeedCursor(mapOf(FeedTier.DISCOVERY to TierCursor(it.post.createdAt, it.post.id)), 0)
                )
            }

        return MixedFeedResponse(
            items = page.map { details2 ->
                FeedItemResponse(
                    post = details2.toResponse(storage, mediaUrlExpiry),
                    source = SOURCE_DISCOVERY,
                    reason = FeedReasonResponse(
                        code = "NO_TOPICS_SELECTED",
                        text = "İlgi alanlarını henüz seçmedin; şimdilik en yeni içerikleri gösteriyoruz.",
                    ),
                )
            },
            mix = FeedMixResponse(primary = 0, related = 0, discovery = page.size),
            personalized = false,
            nextCursor = nextCursor,
        )
    }

    /**
     * İstenen katman boşsa hangi sırayla başka katmandan doldurulacağı.
     * Kullanıcının kendi seçimi her zaman önce gelir.
     */
    private fun fallbackOrder(wanted: FeedTier): List<FeedTier> =
        listOf(wanted) + FALLBACK_PREFERENCE.filter { it != wanted }

    private class ReasonBuilder(userTopics: List<UserTopic>, topicRepository: TopicRepository) {
        private val byTopicId = userTopics.associateBy { it.topic.id }

        /** İlişkili konu -> o ilişkiyi doğuran, kullanıcının seçtiği konu. */
        private val relationSource: Map<UUID, UUID> = topicRepository
            .relationsFor(userTopics.map { it.topic.id })
            .groupBy { it.relatedTopicId }
            .mapValues { (_, relations) -> relations.maxBy { it.weight }.topicId }

        fun build(tier: FeedTier, details: PostDetails): FeedReasonResponse = when (tier) {
            FeedTier.FOLLOWING -> FeedReasonResponse(
                code = "FOLLOWING",
                text = "Takip ettiğin ${details.author.fullName} paylaştı.",
            )

            FeedTier.PRIORITY_TOPIC -> selectedTopic(details)?.let { topic ->
                val position = byTopicId.getValue(topic.id).position + 1
                FeedReasonResponse(
                    code = "TOPIC_PRIORITY",
                    text = "${topic.name}, ilgi sıralamanda $position. sırada.",
                    topic = topic.toSummary(),
                )
            } ?: FeedReasonResponse("TOPIC_PRIORITY", "Öncelikli ilgi alanlarınla eşleşiyor.")

            FeedTier.OTHER_TOPIC -> selectedTopic(details)?.let { topic ->
                FeedReasonResponse(
                    code = "TOPIC_MATCH",
                    text = "${topic.name}, seçtiğin ilgi alanlarından biri.",
                    topic = topic.toSummary(),
                )
            } ?: FeedReasonResponse("TOPIC_MATCH", "Seçtiğin ilgi alanlarıyla eşleşiyor.")

            FeedTier.RELATED_TOPIC -> {
                val related = details.topics.firstOrNull { relationSource.containsKey(it.id) }
                val source = related?.let { byTopicId[relationSource[it.id]]?.topic }
                if (related != null && source != null) {
                    FeedReasonResponse(
                        code = "RELATED_TOPIC",
                        text = "Seçtiğin ${source.name} alanıyla ilişkili: ${related.name}.",
                        topic = related.toSummary(),
                    )
                } else {
                    FeedReasonResponse("RELATED_TOPIC", "İlgi alanlarınla ilişkili bir konudan geldi.")
                }
            }

            FeedTier.DISCOVERY -> FeedReasonResponse(
                code = "DISCOVERY",
                text = "Keşif payından geldi; ilgi alanlarının dışında yeni bir konu.",
                topic = details.topics.firstOrNull()?.toSummary(),
            )
        }

        /** Gönderinin konuları içinde kullanıcının sıralamasında en üstte olanı. */
        private fun selectedTopic(details: PostDetails): Topic? = details.topics
            .filter { byTopicId.containsKey(it.id) }
            .minByOrNull { byTopicId.getValue(it.id).position }
    }

    companion object {
        const val DEFAULT_LIMIT = 20
        const val MAX_LIMIT = 50

        /**
         * On slotluk karışım deseni: 2 takip, 5 ana ilgi (4 öncelikli + 1 alt sıra),
         * 2 ilişkili, 1 keşif.
         *
         * Takip katmanı eklenirken pay **ilgi alanlarından** alındı; ilişkili
         * %20 ve keşif %10 oranlarına dokunulmadı, çünkü bu ikisi ürünün açıkça
         * verdiği "balonunu kırma" sözü. Kullanıcı kimseyi takip etmiyorsa takip
         * slotları geri ilgi alanlarına düşer ve dağılım eski %70/%20/%10 hâline döner.
         */
        val PATTERN = listOf(
            FeedTier.FOLLOWING,
            FeedTier.PRIORITY_TOPIC,
            FeedTier.RELATED_TOPIC,
            FeedTier.PRIORITY_TOPIC,
            FeedTier.FOLLOWING,
            FeedTier.PRIORITY_TOPIC,
            FeedTier.OTHER_TOPIC,
            FeedTier.RELATED_TOPIC,
            FeedTier.PRIORITY_TOPIC,
            FeedTier.DISCOVERY,
        )

        /** Takip de kullanıcının açık tercihi olduğu için ilgi alanlarından önce geliyor. */
        private val FALLBACK_PREFERENCE = listOf(
            FeedTier.FOLLOWING,
            FeedTier.PRIORITY_TOPIC,
            FeedTier.OTHER_TOPIC,
            FeedTier.RELATED_TOPIC,
            FeedTier.DISCOVERY,
        )

        private val TIER_KEYS = mapOf(
            FeedTier.FOLLOWING to "f",
            FeedTier.PRIORITY_TOPIC to "p",
            FeedTier.OTHER_TOPIC to "o",
            FeedTier.RELATED_TOPIC to "r",
            FeedTier.DISCOVERY to "d",
        )

        internal fun encodeCursor(cursor: MixedFeedCursor): String {
            val parts = cursor.tiers.entries
                .sortedBy { it.key.ordinal }
                .map { (tier, tierCursor) ->
                    "${TIER_KEYS.getValue(tier)}=${tierCursor.createdAt.toEpochMilli()}|${tierCursor.id}"
                } + "s=${cursor.slot}"
            return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(parts.joinToString(";").toByteArray(StandardCharsets.UTF_8))
        }

        internal fun decodeCursor(rawCursor: String): MixedFeedCursor = try {
            val decoded = String(Base64.getUrlDecoder().decode(rawCursor), StandardCharsets.UTF_8)
            var slot = 0
            val tiers = mutableMapOf<FeedTier, TierCursor>()
            decoded.split(';').filter { it.isNotBlank() }.forEach { part ->
                val (key, value) = part.split('=', limit = 2)
                if (key == "s") {
                    slot = value.toInt()
                } else {
                    val tier = TIER_KEYS.entries.first { it.value == key }.key
                    val (millis, id) = value.split('|', limit = 2)
                    tiers[tier] = TierCursor(Instant.ofEpochMilli(millis.toLong()), UUID.fromString(id))
                }
            }
            MixedFeedCursor(tiers, slot)
        } catch (_: Throwable) {
            throw ApiException(HttpStatusCode.BadRequest, "INVALID_CURSOR", "Akış imleci geçersiz.", "cursor")
        }
    }
}
