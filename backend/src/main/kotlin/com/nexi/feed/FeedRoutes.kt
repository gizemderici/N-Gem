package com.nexi.feed

import com.nexi.auth.authenticatedUserId
import com.nexi.recommendations.FeedRecommendationContext
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.authenticate
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import java.util.UUID

/**
 * İki yol da aynı politikaya bağlı.
 *
 * `/api/v1/feed` eskiden ayrı bir konu karışımı döndürüyordu ve hiçbir istemci
 * onu çağırmıyordu; `/api/v1/posts/feed` ise davranıştan öğreniyordu. Tek
 * politika altında birleştikleri için ikisi de aynı yanıtı veriyor — konu
 * tercihi artık sıralamanın içindeki bir aday kaynağı.
 */
fun Route.feedRoutes(policy: FeedPolicy) {
    authenticate("auth-jwt") {
        get("/api/v1/feed") {
            call.respond(policy.feed(call.authenticatedUserId(), call.feedQuery()))
        }
    }
}

/** Akış sorgusunun ortak ayrıştırması; iki yol da aynı parametreleri okuyor. */
data class FeedQuery(
    val cursor: String?,
    val limit: Int?,
    val context: FeedRecommendationContext?,
    val personalizationEnabled: Boolean,
)

fun ApplicationCall.feedQuery(): FeedQuery {
    val localHour = request.queryParameters["localHour"]?.toIntOrNull()?.coerceIn(0, 23)
    val timezoneOffset = request.queryParameters["timezoneOffsetMinutes"]?.toIntOrNull()?.coerceIn(-840, 840)
    val sessionId = request.queryParameters["sessionId"]?.let { raw ->
        runCatching { UUID.fromString(raw) }.getOrNull()
    }
    return FeedQuery(
        cursor = request.queryParameters["cursor"],
        limit = request.queryParameters["limit"]?.toIntOrNull(),
        context = if (localHour != null && timezoneOffset != null && sessionId != null) {
            FeedRecommendationContext(localHour, timezoneOffset, sessionId)
        } else {
            null
        },
        personalizationEnabled = request.queryParameters["personalized"]?.toBooleanStrictOrNull() ?: true,
    )
}

fun FeedPolicy.feed(viewerId: UUID, query: FeedQuery) =
    feed(viewerId, query.cursor, query.limit, query.context, query.personalizationEnabled)
