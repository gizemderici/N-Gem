package com.nexi.media

import com.nexi.auth.ApiException
import com.nexi.auth.MessageResponse
import com.nexi.auth.authenticatedUserId
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import java.util.UUID

fun Route.mediaRoutes(service: MediaService) {
    authenticate("auth-jwt") {
        route("/api/v1/media") {
            post("/uploads") {
                call.respond(
                    HttpStatusCode.Created,
                    service.createUpload(call.authenticatedUserId(), call.receive<CreateMediaUploadRequest>()),
                )
            }
            post("/{id}/complete") {
                // Gövde isteğe bağlı: yalnızca video için kapak görseli taşıyor.
                val request = runCatching { call.receive<CompleteMediaUploadRequest>() }
                    .getOrElse { CompleteMediaUploadRequest() }
                call.respond(service.completeUpload(call.authenticatedUserId(), call.mediaId(), request))
            }
            get("/{id}") {
                call.respond(service.get(call.authenticatedUserId(), call.mediaId()))
            }
            /** İstemci videonun oynatılabilir hale gelmesini bu uçtan yokluyor. */
            get("/{id}/status") {
                call.respond(service.status(call.authenticatedUserId(), call.mediaId()))
            }
            delete("/{id}") {
                service.delete(call.authenticatedUserId(), call.mediaId())
                call.respond(MessageResponse("Medya silindi."))
            }
        }
    }
}

private fun io.ktor.server.application.ApplicationCall.mediaId(): UUID {
    val rawId = parameters["id"]
    return runCatching { UUID.fromString(rawId) }.getOrElse {
        throw ApiException(HttpStatusCode.BadRequest, "INVALID_MEDIA_ID", "Medya kimliği geçersiz.", "id")
    }
}
