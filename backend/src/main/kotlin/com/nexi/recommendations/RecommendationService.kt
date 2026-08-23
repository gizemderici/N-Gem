package com.nexi.recommendations

import com.nexi.auth.ApiException
import com.nexi.auth.MessageResponse
import com.nexi.auth.validation
import com.nexi.posts.PostRepository
import io.ktor.http.HttpStatusCode
import java.time.Clock
import java.time.Instant
import java.util.UUID

class RecommendationService(
    private val repository: RecommendationRepository,
    private val postRepository: PostRepository,
    private val ranker: ContextualRanker,
    private val clock: Clock = Clock.systemUTC(),
) {
    fun append(userId: UUID, request: RecommendationEventBatchRequest): RecommendationEventBatchResponse {
        if (request.events.isEmpty()) return RecommendationEventBatchResponse(0, 0)
        if (request.events.size > 100) throw validation("TOO_MANY_EVENTS", "Bir istekte en fazla 100 olay gönderilebilir.", "events")
        val now = clock.instant()
        val parsed = request.events.map { parse(userId, it, now) }
        parsed.mapNotNull(RecommendationEvent::postId).distinct().forEach { postId ->
            if (postRepository.findDetails(postId, userId) == null) {
                throw ApiException(HttpStatusCode.NotFound, "POST_NOT_FOUND", "Olayın gönderisi bulunamadı.", "postId")
            }
        }
        val accepted = repository.append(parsed)
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
        val target = request.targetFeature?.trim()?.takeIf(String::isNotBlank)?.take(80)
        if (request.eventType == RecommendationEventType.INTEREST_SELECTED && target == null) {
            throw validation("MISSING_TARGET_FEATURE", "İlgi seçimi olayında hedef özellik gerekli.", "targetFeature")
        }
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
        )
    }
}

private fun String.uuid(field: String): UUID = runCatching { UUID.fromString(this) }.getOrElse {
    throw validation("INVALID_ID", "$field geçerli bir UUID olmalı.", field)
}
