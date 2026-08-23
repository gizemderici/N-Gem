package com.nexi.moderation

import com.nexi.auth.ApiException
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

fun Route.moderationRoutes(service: ModerationService) {
    authenticate("auth-jwt") {
        put("/api/v1/users/{username}/block") {
            call.respond(service.setBlock(call.authenticatedUserId(), call.username(), true))
        }

        delete("/api/v1/users/{username}/block") {
            call.respond(service.setBlock(call.authenticatedUserId(), call.username(), false))
        }

        get("/api/v1/users/me/blocked") {
            call.respond(
                service.blockedUsers(
                    blockerId = call.authenticatedUserId(),
                    rawCursor = call.request.queryParameters["cursor"],
                    requestedLimit = call.request.queryParameters["limit"]?.toIntOrNull(),
                )
            )
        }

        post("/api/v1/reports") {
            val response = service.report(call.authenticatedUserId(), call.receive<CreateReportRequest>())
            // Aynı hedef ikinci kez şikâyet edildiğinde yeni kayıt oluşmuyor.
            call.respond(if (response.alreadyReported) HttpStatusCode.OK else HttpStatusCode.Created, response)
        }

        get("/api/v1/users/me/reports") {
            call.respond(
                service.reports(
                    reporterId = call.authenticatedUserId(),
                    rawCursor = call.request.queryParameters["cursor"],
                    requestedLimit = call.request.queryParameters["limit"]?.toIntOrNull(),
                )
            )
        }
    }
}

private fun ApplicationCall.username(): String = parameters["username"]?.takeIf { it.isNotBlank() }
    ?: throw ApiException(HttpStatusCode.BadRequest, "INVALID_USERNAME", "Kullanıcı adı geçersiz.", "username")
