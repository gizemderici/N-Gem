package com.nexi.recommendations

import com.nexi.auth.ApiException
import com.nexi.auth.MessageResponse
import com.nexi.auth.validation
import com.nexi.posts.PostRepository
import com.nexi.topics.TopicResolver
import io.ktor.http.HttpStatusCode
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Instant
import java.util.UUID

class RecommendationService(
    private val repository: RecommendationRepository,
    private val postRepository: PostRepository,
    private val ranker: ContextualRanker,
    private val topics: TopicResolver,
    private val clock: Clock = Clock.systemUTC(),
    private val consents: ConsentRepository = AlwaysGrantedConsentRepository,
) {
    fun append(userId: UUID, request: RecommendationEventBatchRequest): RecommendationEventBatchResponse {
        if (request.events.isEmpty()) return RecommendationEventBatchResponse(0, 0)
        if (request.events.size > 100) throw validation("TOO_MANY_EVENTS", "Bir istekte en fazla 100 olay gönderilebilir.", "events")
        val now = clock.instant()

        // Rıza yoksa davranış olayı hiç yazılmaz. İstemciye hata değil,
        // "hepsi yok sayıldı" dönüyoruz: rızayı geri çekmek istemciyi hata
        // döngüsüne sokmamalı, kuyruğunu boşaltabilmeli.
        if (!consents.find(userId).granted) {
            return RecommendationEventBatchResponse(accepted = 0, ignored = request.events.size)
        }
        val parsed = request.events.map { parse(userId, it, now) }
        parsed.mapNotNull(RecommendationEvent::postId).distinct().forEach { postId ->
            if (postRepository.findDetails(postId, userId) == null) {
                throw ApiException(HttpStatusCode.NotFound, "POST_NOT_FOUND", "Olayın gönderisi bulunamadı.", "postId")
            }
        }
        val accepted = repository.append(parsed)

        // Gizleme yalnızca sıralamada olumsuz puan olarak kalırsa gönderi
        // ertesi gün yine akışa girer. Kullanıcının açık tercihi olduğu için
        // aday havuzundan kalıcı olarak çıkarılmalı.
        parsed.filter { it.eventType == RecommendationEventType.CONTENT_HIDDEN }
            .mapNotNull(RecommendationEvent::postId)
            .distinct()
            .forEach { postId -> repository.hide(userId, postId, now) }

        return RecommendationEventBatchResponse(accepted, parsed.size - accepted)
    }

    fun profile(userId: UUID, localHour: Int?): RecommendationProfileResponse {
        val stats = repository.profileStats(userId)
        val signals = repository.recentSignals(userId, 2_000)
        val (interests, periods) = ranker.profile(signals, localHour?.coerceIn(0, 23) ?: 12, clock.instant())
        return RecommendationProfileResponse(
            modelVersion = ranker.modelVersion,
            eventCount = stats.eventCount,
            topInterests = interests,
            timePreferences = periods,
            lastUpdatedAt = stats.lastUpdatedAt?.toString(),
        )
    }

    fun reset(userId: UUID): MessageResponse {
        repository.clear(userId)
        return MessageResponse("Öğrenilmiş öneri profili sıfırlandı.")
    }

    /**
     * Kullanıcının kendi öneri verisini indirmesi.
     *
     * Profil özeti "ne öğrendik" sorusunu cevaplıyor ama ham veriyi
     * göstermiyordu; KVKK ve GDPR erişim hakkı özetle değil, kaydın kendisiyle
     * karşılanır.
     */
    fun export(userId: UUID): RecommendationExportResponse {
        val data = repository.export(userId)
        return RecommendationExportResponse(
            exportedAt = clock.instant().toString(),
            contractVersion = EventContract.CURRENT_VERSION,
            consent = consents.find(userId).toResponse(),
            counts = mapOf(
                "events" to data.events.size,
                "feedRequests" to data.feedRequests.size,
                "feedCandidates" to data.feedCandidates.size,
                "featureSnapshots" to data.featureSnapshots.size,
                "hiddenPosts" to data.hiddenPosts.size,
            ),
            data = data,
        )
    }

    fun consent(userId: UUID): ConsentResponse = consents.find(userId).toResponse()

    /**
     * Rıza geri çekildiğinde öğrenilmiş profil de silinir.
     *
     * Yalnızca bayrağı kapatmak, toplanan veriyi yerinde bırakırdı: kullanıcı
     * kişiselleştirmeyi kapattığında geçmişinin de gitmesini bekler.
     */
    fun setConsent(userId: UUID, request: UpdateConsentRequest): ConsentResponse {
        val updated = consents.set(userId, request.granted, ConsentContract.VERSION, clock.instant())
        if (!request.granted) repository.clear(userId)
        return updated.toResponse()
    }

    private fun RecommendationConsent.toResponse() = ConsentResponse(
        granted = granted,
        contractVersion = contractVersion,
        currentContractVersion = ConsentContract.VERSION,
        updatedAt = updatedAt?.toString(),
    )

    private fun parse(userId: UUID, request: RecommendationEventRequest, receivedAt: Instant): RecommendationEvent {
        val clientEventId = request.clientEventId.uuid("clientEventId")
        val sessionId = request.sessionId.uuid("sessionId")
        val feedRequestId = request.feedRequestId?.uuid("feedRequestId")
        val postId = request.postId?.uuid("postId")
        val occurredAt = runCatching { Instant.parse(request.occurredAt) }.getOrElse {
            throw validation("INVALID_OCCURRED_AT", "Olay zamanı ISO-8601 biçiminde olmalı.", "occurredAt")
        }
        if (occurredAt.isAfter(receivedAt.plusSeconds(300)) || occurredAt.isBefore(receivedAt.minusSeconds(86_400 * 30L))) {
            throw validation("INVALID_OCCURRED_AT", "Olay zamanı kabul edilen aralığın dışında.", "occurredAt")
        }
        if (request.localHour !in 0..23) throw validation("INVALID_LOCAL_HOUR", "Yerel saat 0-23 arasında olmalı.", "localHour")
        if (request.timezoneOffsetMinutes !in -840..840) throw validation("INVALID_TIMEZONE", "Saat dilimi farkı geçersiz.", "timezoneOffsetMinutes")
        if (request.position != null && request.position !in 0..999) throw validation("INVALID_POSITION", "Akış konumu geçersiz.", "position")
        if (request.dwellMillis != null && request.dwellMillis !in 0..86_400_000) throw validation("INVALID_DWELL", "İçerikte kalma süresi geçersiz.", "dwellMillis")
        if (request.completionRatio != null && request.completionRatio !in 0.0..1.0) throw validation("INVALID_COMPLETION", "Tamamlama oranı geçersiz.", "completionRatio")
        val surface = request.surface.trim().lowercase().take(40)
        if (surface.isBlank()) throw validation("INVALID_SURFACE", "Yüzey bilgisi gerekli.", "surface")
        if (request.eventType in SERVER_GENERATED) {
            throw validation(
                "SERVER_ONLY_EVENT",
                "Bu olayı yalnızca backend üretir; istemcinin göndermesi gerekmez.",
                "eventType",
            )
        }
        val schemaVersion = validateSchemaVersion(request)
        val platform = request.platform?.let {
            EventPlatform.fromClient(it) ?: throw validation("INVALID_PLATFORM", "Platform bilgisi geçersiz.", "platform")
        }
        val appVersion = request.appVersion?.trim()?.takeIf(String::isNotBlank)?.let {
            if (it.length > EventContract.MAX_APP_VERSION_LENGTH) {
                throw validation("INVALID_APP_VERSION", "Uygulama sürümü çok uzun.", "appVersion")
            }
            it
        }
        val target = resolveTargetFeature(request)
        if (request.eventType !in setOf(RecommendationEventType.SESSION_STARTED, RecommendationEventType.INTEREST_SELECTED) && postId == null) {
            throw validation("MISSING_POST_ID", "İçerik olayında gönderi kimliği gerekli.", "postId")
        }
        return RecommendationEvent(
            id = UUID.randomUUID(),
            userId = userId,
            postId = postId,
            clientEventId = clientEventId,
            sessionId = sessionId,
            feedRequestId = feedRequestId,
            eventType = request.eventType,
            surface = surface,
            position = request.position,
            dwellMillis = request.dwellMillis,
            completionRatio = request.completionRatio,
            localHour = request.localHour,
            timezoneOffsetMinutes = request.timezoneOffsetMinutes,
            targetFeature = target,
            occurredAt = occurredAt,
            receivedAt = receivedAt,
            schemaVersion = schemaVersion,
            appVersion = appVersion,
            platform = platform,
        )
    }

    /**
     * Gelecekten bir sürüm kabul edilmez: anlamını bilmediğimiz bir olayı
     * yazmak, veriyi sonradan ayıklanamaz hâle getirir. Eski ama desteklenen
     * sürümler kabul edilip loglanır; hangi istemci sürümünün ne kadar eski
     * veri ürettiği `schema_version` kolonundan sorgulanabilir.
     */
    private fun validateSchemaVersion(request: RecommendationEventRequest): Int {
        val version = request.schemaVersion
        if (version < EventContract.OLDEST_SUPPORTED_VERSION || version > EventContract.CURRENT_VERSION) {
            throw validation(
                "UNSUPPORTED_SCHEMA_VERSION",
                "Olay sözleşmesi sürümü desteklenmiyor: $version.",
                "schemaVersion",
            )
        }
        if (version < EventContract.CURRENT_VERSION) {
            logger.info(
                "Eski olay sözleşmesi sürümü: schemaVersion={} platform={} appVersion={}",
                version, request.platform, request.appVersion,
            )
        }
        return version
    }

    /**
     * İlgi seçimi olayının hedefi kanonik konu slug'ına çevrilir.
     *
     * İstemci slug ya da konu kimliği gönderebilir; depoya her zaman slug
     * yazılır, böylece mobil, backend ve çevrimdışı değerlendirici aynı kimliği
     * görür. Tanınmayan değer 400 döner: eskiden serbest metin kabul ediliyordu
     * ve mobildeki uyuşmayan katalog yüzünden aylarca hiçbir konuyla
     * eşleşmeyen kayıtlar birikmişti.
     */
    private fun resolveTargetFeature(request: RecommendationEventRequest): String? {
        val raw = request.targetFeature?.trim()?.takeIf(String::isNotBlank)
        if (request.eventType != RecommendationEventType.INTEREST_SELECTED) return raw?.take(80)
        if (raw == null) {
            throw validation("MISSING_TARGET_FEATURE", "İlgi seçimi olayında hedef özellik gerekli.", "targetFeature")
        }
        return topics.canonicalSlug(raw)
            ?: throw validation("UNKNOWN_TOPIC_FEATURE", "İlgi alanı katalogda yok.", "targetFeature")
    }

    companion object {
        private val logger = LoggerFactory.getLogger(RecommendationService::class.java)

        /**
         * Backend'in kendi işlemi içinde ürettiği olaylar.
         *
         * Beğeni, kaydetme ve şikâyet zaten backend'de yazılan işlemler;
         * sinyali orada üretmek istemcinin ağı kesilse de kaybolmamasını
         * sağlıyor. İstemci ayrıca göndermeye çalışırsa aynı etkileşim iki kez
         * sayılırdı, o yüzden reddediliyor.
         */
        internal val SERVER_GENERATED = setOf(
            RecommendationEventType.FEED_SERVED,
            RecommendationEventType.CONTENT_LIKED,
            RecommendationEventType.CONTENT_SAVED,
            RecommendationEventType.CONTENT_REPORTED,
        )
    }
}

private fun String.uuid(field: String): UUID = runCatching { UUID.fromString(this) }.getOrElse {
    throw validation("INVALID_ID", "$field geçerli bir UUID olmalı.", field)
}
