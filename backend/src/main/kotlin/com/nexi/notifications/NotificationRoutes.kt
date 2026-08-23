package com.nexi.notifications

import com.nexi.auth.ApiException
import com.nexi.auth.MessageResponse
import com.nexi.auth.authenticatedUserId
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.authenticate
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import java.util.UUID

fun Route.notificationRoutes(service: NotificationService) {
    authenticate("auth-jwt") {
        route("/api/v1/notifications") {
            get {
                call.respond(
                    service.page(
                        userId = call.authenticatedUserId(),
                        rawCursor = call.request.queryParameters["cursor"],
                        requestedLimit = call.request.queryParameters["limit"]?.toIntOrNull(),
                    )
                )
            }

            get("/unread-count") {
                call.respond(service.unreadCount(call.authenticatedUserId()))
            }

            // `read-all` literal segmenti `{id}` deseninden önce eşleşir.
            put("/read-all") {
                call.respond(service.markAllRead(call.authenticatedUserId()))
            }

            put("/{id}/read") {
                call.respond(service.markRead(call.authenticatedUserId(), call.notificationId()))
            }

            delete("/{id}") {
                service.delete(call.authenticatedUserId(), call.notificationId())
                call.respond(MessageResponse("Bildirim silindi."))
            }
        }
    }
}

private fun ApplicationCall.notificationId(): UUID =
    runCatching { UUID.fromString(parameters["id"]) }.getOrElse {
        throw ApiException(HttpStatusCode.BadRequest, "INVALID_NOTIFICATION_ID", "Bildirim kimliği geçersiz.", "id")
    }
