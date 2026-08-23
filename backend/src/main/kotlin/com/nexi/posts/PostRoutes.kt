package com.nexi.posts

import com.nexi.auth.ApiException
import com.nexi.auth.MessageResponse
import com.nexi.auth.authenticatedUserId
import com.nexi.recommendations.FeedRecommendationContext
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import java.util.UUID

fun Route.postRoutes(service: PostService) {
    authenticate("auth-jwt") {
        route("/api/v1/posts") {
            post {
                call.respond(HttpStatusCode.Created, service.create(call.authenticatedUserId(), call.receive<CreatePostRequest>()))
            }
            get("/feed") {
                val limit = call.request.queryParameters["limit"]?.toIntOrNull()
                val localHour = call.request.queryParameters["localHour"]?.toIntOrNull()?.coerceIn(0, 23)
                val timezoneOffset = call.request.queryParameters["timezoneOffsetMinutes"]?.toIntOrNull()?.coerceIn(-840, 840)
                val sessionId = call.request.queryParameters["sessionId"]?.let { raw ->
                    runCatching { UUID.fromString(raw) }.getOrNull()
                }
                val context = if (localHour != null && timezoneOffset != null && sessionId != null) {
                    FeedRecommendationContext(localHour, timezoneOffset, sessionId)
                } else null
                val personalizationEnabled = call.request.queryParameters["personalized"]?.toBooleanStrictOrNull() ?: true
                call.respond(
                    service.feed(
                        call.authenticatedUserId(),
                        call.request.queryParameters["cursor"],
                        limit,
                        context,
                        personalizationEnabled,
                    )
                )
            }
            get("/{id}") {
                call.respond(service.get(call.authenticatedUserId(), call.postId()))
            }
            delete("/{id}") {
                service.delete(call.authenticatedUserId(), call.postId())
                call.respond(MessageResponse("Gönderi silindi."))
            }
            put("/{id}/like") {
                call.respond(service.setLike(call.authenticatedUserId(), call.postId(), true))
            }
            delete("/{id}/like") {
                call.respond(service.setLike(call.authenticatedUserId(), call.postId(), false))
            }
            put("/{id}/save") {
                call.respond(service.setSave(call.authenticatedUserId(), call.postId(), true))
            }
            delete("/{id}/save") {
                call.respond(service.setSave(call.authenticatedUserId(), call.postId(), false))
            }
        }
    }
}

private fun io.ktor.server.application.ApplicationCall.postId(): UUID {
    val rawId = parameters["id"]
    return runCatching { UUID.fromString(rawId) }.getOrElse {
        throw ApiException(HttpStatusCode.BadRequest, "INVALID_POST_ID", "Gönderi kimliği geçersiz.", "id")
    }
}
