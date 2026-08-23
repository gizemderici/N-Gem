package com.nexi.messaging

import com.nexi.auth.ApiException
import com.nexi.auth.MessageResponse as ApiMessageResponse
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

fun Route.messagingRoutes(service: MessagingService) {
    authenticate("auth-jwt") {
        route("/api/v1/conversations") {
            post {
                call.respond(
                    HttpStatusCode.Created,
                    service.openConversation(call.authenticatedUserId(), call.receive<CreateConversationRequest>()),
                )
            }

            get {
                call.respond(
                    service.conversations(
                        viewerId = call.authenticatedUserId(),
                        rawCursor = call.request.queryParameters["cursor"],
                        requestedLimit = call.request.queryParameters["limit"]?.toIntOrNull(),
                    )
                )
            }

            get("/{id}/messages") {
                call.respond(
                    service.messages(
                        viewerId = call.authenticatedUserId(),
                        conversationId = call.conversationId(),
                        rawCursor = call.request.queryParameters["cursor"],
                        requestedLimit = call.request.queryParameters["limit"]?.toIntOrNull(),
                    )
                )
            }

            post("/{id}/messages") {
                call.respond(
                    HttpStatusCode.Created,
                    service.send(call.authenticatedUserId(), call.conversationId(), call.receive<SendMessageRequest>()),
                )
            }

            put("/{id}/read") {
                call.respond(service.markRead(call.authenticatedUserId(), call.conversationId()))
            }
        }

        delete("/api/v1/messages/{id}") {
            service.deleteMessage(call.authenticatedUserId(), call.messageId())
            call.respond(ApiMessageResponse("Mesaj silindi."))
        }
    }
}

private fun ApplicationCall.conversationId(): UUID =
    runCatching { UUID.fromString(parameters["id"]) }.getOrElse {
        throw ApiException(HttpStatusCode.BadRequest, "INVALID_CONVERSATION_ID", "Konuşma kimliği geçersiz.", "id")
    }

private fun ApplicationCall.messageId(): UUID =
    runCatching { UUID.fromString(parameters["id"]) }.getOrElse {
        throw ApiException(HttpStatusCode.BadRequest, "INVALID_MESSAGE_ID", "Mesaj kimliği geçersiz.", "id")
    }
