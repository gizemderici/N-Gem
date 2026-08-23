package com.nexi.comments

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
import java.util.UUID

fun Route.commentRoutes(service: CommentService) {
    authenticate("auth-jwt") {
        post("/api/v1/posts/{postId}/comments") {
            call.respond(
                HttpStatusCode.Created,
                service.create(
                    authorId = call.authenticatedUserId(),
                    postId = call.uuid("postId", "INVALID_POST_ID", "Gönderi kimliği geçersiz."),
                    request = call.receive<CreateCommentRequest>(),
                ),
            )
        }

        get("/api/v1/posts/{postId}/comments") {
            call.respond(
                service.page(
                    viewerId = call.authenticatedUserId(),
                    postId = call.uuid("postId", "INVALID_POST_ID", "Gönderi kimliği geçersiz."),
                    rawCursor = call.request.queryParameters["cursor"],
                    requestedLimit = call.request.queryParameters["limit"]?.toIntOrNull(),
                )
            )
        }

        delete("/api/v1/comments/{id}") {
            service.delete(
                viewerId = call.authenticatedUserId(),
                commentId = call.uuid("id", "INVALID_COMMENT_ID", "Yorum kimliği geçersiz."),
            )
            call.respond(MessageResponse("Yorum silindi."))
        }
    }
}

private fun ApplicationCall.uuid(name: String, code: String, message: String): UUID =
    runCatching { UUID.fromString(parameters[name]) }.getOrElse {
        throw ApiException(HttpStatusCode.BadRequest, code, message, name)
    }
