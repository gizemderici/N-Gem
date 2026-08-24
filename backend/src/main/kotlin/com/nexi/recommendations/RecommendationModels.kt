package com.nexi.recommendations

import com.nexi.posts.CandidateSource
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.Instant
import java.util.UUID

@Serializable
enum class RecommendationEventType {
    @SerialName("session_started") SESSION_STARTED,
    @SerialName("interest_selected") INTEREST_SELECTED,

    /**
     * Backend bir gönderiyi akışa koydu. Kullanıcının gördüğünün kanıtı
     * değildir; yalnızca aday havuzundan seçildiğini söyler.
     *
     * Bu ayrım olmadan sunum kaydı `content_impression` olarak yazılıyordu ve
     * gösterim satırlarının tamamı aslında sunum kaydıydı. Eğitim verisi bu
     * tablodan üretileceği için ikisinin karışması modele "gösterildi" diye
     * hiç görülmemiş içerik öğretirdi. Yalnızca sunucu üretir; istemciden
     * gelirse reddedilir.
     */
    @SerialName("feed_served") FEED_SERVED,

    /** Gönderi ekranda gerçekten görünür oldu; istemci ölçer. */
    @SerialName("content_impression") CONTENT_IMPRESSION,
    @SerialName("content_view") CONTENT_VIEW,
    @SerialName("content_complete") CONTENT_COMPLETE,
    @SerialName("content_liked") CONTENT_LIKED,
    @SerialName("content_saved") CONTENT_SAVED,
    @SerialName("content_shared") CONTENT_SHARED,
    @SerialName("content_hidden") CONTENT_HIDDEN,
    @SerialName("content_reported") CONTENT_REPORTED,
    @SerialName("recommendation_reason_opened") RECOMMENDATION_REASON_OPENED,
}

@Serializable
data class RecommendationEventRequest(
    val clientEventId: String,
    val sessionId: String,
    val feedRequestId: String? = null,
    val postId: String? = null,
    val eventType: RecommendationEventType,
    val surface: String = "feed",
    val position: Int? = null,
    val dwellMillis: Long? = null,
    val completionRatio: Double? = null,
    val localHour: Int,
    val timezoneOffsetMinutes: Int,
    val targetFeature: String? = null,
    val occurredAt: String,
    /**
     * Olayı üreten istemcinin uyduğu sözleşme sürümü. Varsayılan 1, çünkü
     * alanı hiç göndermeyen istemciler sözleşmenin ilk sürümüne göre yazılmış
     * demektir.
     */
    val schemaVersion: Int = EventContract.OLDEST_SUPPORTED_VERSION,
    val appVersion: String? = null,
    val platform: String? = null,
)

/** Olay sözleşmesinin sürüm sınırları; ayrıntısı `docs/event-contract-v1.md`. */
object EventContract {
    const val CURRENT_VERSION = 2
    const val OLDEST_SUPPORTED_VERSION = 1
    const val MAX_APP_VERSION_LENGTH = 20
}

@Serializable
enum class EventPlatform {
    @SerialName("ios") IOS,
    @SerialName("android") ANDROID,
    @SerialName("web") WEB,

    /** Sunucunun kendi ürettiği olaylar; istemci bu değeri gönderemez. */
    @SerialName("backend") BACKEND;

    /** Veritabanındaki `platform` kolonuna yazılan değer. */
    val wireName: String get() = name.lowercase()

    companion object {
        fun fromClient(raw: String): EventPlatform? =
            entries.firstOrNull { it.wireName == raw.trim().lowercase() && it != BACKEND }
    }
}

@Serializable
data class RecommendationEventBatchRequest(val events: List<RecommendationEventRequest>)

@Serializable
data class RecommendationEventBatchResponse(val accepted: Int, val ignored: Int)

@Serializable
data class RecommendationProfileResponse(
    val modelVersion: String,
    val eventCount: Long,
    val topInterests: List<RecommendationAffinityResponse>,
    val timePreferences: List<RecommendationTimePreferenceResponse>,
    val lastUpdatedAt: String?,
)

@Serializable
data class RecommendationAffinityResponse(
    val key: String,
    val label: String,
    val score: Double,
)

@Serializable
data class RecommendationTimePreferenceResponse(
    val period: String,
    val score: Double,
)

data class RecommendationEvent(
    val id: UUID,
    val userId: UUID,
    val postId: UUID?,
    val clientEventId: UUID,
    val sessionId: UUID,
    val feedRequestId: UUID?,
    val eventType: RecommendationEventType,
    val surface: String,
    val position: Int?,
    val dwellMillis: Long?,
    val completionRatio: Double?,
    val localHour: Int,
    val timezoneOffsetMinutes: Int,
    val targetFeature: String?,
    val occurredAt: Instant,
    val receivedAt: Instant,
    val schemaVersion: Int = EventContract.CURRENT_VERSION,
    val appVersion: String? = null,
    val platform: EventPlatform? = null,
    /** Sunum kaydında gönderinin hangi aday kaynağından geldiği. */
    val candidateSource: CandidateSource? = null,
)

data class RecommendationSignal(
    val eventType: RecommendationEventType,
    val postId: UUID?,
    val authorId: UUID?,
    val body: String?,
    /** Gönderiye yazarının eklediği konuların slug'ları; konu yakınlığı buradan gelir. */
    val topicSlugs: List<String> = emptyList(),
    val mediaType: String?,
    val dwellMillis: Long?,
    val completionRatio: Double?,
    val localHour: Int,
    val targetFeature: String?,
    val occurredAt: Instant,
)

data class RecommendationProfileStats(
    val eventCount: Long,
    val lastUpdatedAt: Instant?,
)

data class FeedRecommendationContext(
    val localHour: Int,
    val timezoneOffsetMinutes: Int,
    val sessionId: UUID,
)
