package com.nexi.feed

import com.nexi.posts.FeedTier
import com.nexi.posts.PostResponse
import com.nexi.topics.TopicSummaryResponse
import kotlinx.serialization.Serializable
import java.time.Instant
import java.util.UUID

const val SOURCE_FOLLOWING = "FOLLOWING"
const val SOURCE_PRIMARY = "PRIMARY"
const val SOURCE_RELATED = "RELATED"
const val SOURCE_DISCOVERY = "DISCOVERY"

/**
 * Beş iç katman, mobil tarafın gördüğü dört kaynağa indirgenir:
 * öncelikli ve alt sıradaki ilgi alanları birlikte "ana ilgi" sayılır.
 */
fun FeedTier.feedSource(): String = when (this) {
    FeedTier.FOLLOWING -> SOURCE_FOLLOWING
    FeedTier.PRIORITY_TOPIC, FeedTier.OTHER_TOPIC -> SOURCE_PRIMARY
    FeedTier.RELATED_TOPIC -> SOURCE_RELATED
    FeedTier.DISCOVERY -> SOURCE_DISCOVERY
}

/** Tek bir katmanın kronolojik sayfalama imleci. */
data class TierCursor(val createdAt: Instant, val id: UUID)

/**
 * Karışık akışın imleci: her katmanın nerede kaldığı ve karışım deseninde
 * kaçıncı slotta olduğumuz. Desen slotu taşınmazsa her sayfa baştan başlar
 * ve oranlar sayfa sınırlarında bozulur.
 */
data class MixedFeedCursor(
    val tiers: Map<FeedTier, TierCursor>,
    val slot: Int,
)

@Serializable
data class FeedReasonResponse(
    val code: String,
    val text: String,
    val topic: TopicSummaryResponse? = null,
)

@Serializable
data class FeedItemResponse(
    val post: PostResponse,
    val source: String,
    val reason: FeedReasonResponse,
)

/** Bu sayfada hangi kaynaktan kaçar içerik geldiği; şeffaflık ekranı bunu gösterebilir. */
@Serializable
data class FeedMixResponse(
    val primary: Int,
    val related: Int,
    val discovery: Int,
    /** Sonradan eklendi; alanı tanımayan eski istemciler yok sayabilir. */
    val following: Int = 0,
)

@Serializable
data class MixedFeedResponse(
    val items: List<FeedItemResponse>,
    val mix: FeedMixResponse,
    val personalized: Boolean,
    val nextCursor: String? = null,
)
