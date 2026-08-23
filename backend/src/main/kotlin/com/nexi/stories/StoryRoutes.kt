package com.nexi.stories

import com.nexi.auth.ApiException
import com.nexi.auth.MessageResponse
import com.nexi.auth.authenticatedUserId
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
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

fun Route.storyRoutes(service: StoryService) {
    authenticate("auth-jwt") {
        route("/api/v1/stories") {
            post {
                call.respond(
                    HttpStatusCode.Created,
                    service.create(call.authenticatedUserId(), call.receive<CreateStoryRequest>()),
                )
            }

            get("/feed") {
                call.respond(service.feed(call.authenticatedUserId()))
            }

            put("/{id}/view") {
                call.respond(service.markViewed(call.authenticatedUserId(), call.storyId()))
            }

            get("/{id}/viewers") {
                call.respond(
                    service.viewers(
                        viewerId = call.authenticatedUserId(),
                        storyId = call.storyId(),
                        rawCursor = call.request.queryParameters["cursor"],
                        requestedLimit = call.request.queryParameters["limit"]?.toIntOrNull(),
                    )
                )
            }

            delete("/{id}") {
                service.delete(call.authenticatedUserId(), call.storyId())
                call.respond(MessageResponse("Hikâye silindi."))
            }
        }

        get("/api/v1/users/{username}/stories") {
            val username = call.parameters["username"]?.takeIf { it.isNotBlank() }
                ?: throw ApiException(HttpStatusCode.BadRequest, "INVALID_USERNAME", "Kullanıcı adı geçersiz.", "username")
            call.respond(service.byUsername(call.authenticatedUserId(), username))
        }
    }
}

private fun ApplicationCall.storyId(): UUID =
    runCatching { UUID.fromString(parameters["id"]) }.getOrElse {
        throw ApiException(HttpStatusCode.BadRequest, "INVALID_STORY_ID", "Hikâye kimliği geçersiz.", "id")
    }
