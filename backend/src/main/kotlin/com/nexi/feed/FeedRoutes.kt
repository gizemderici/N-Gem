package com.nexi.feed

import com.nexi.auth.authenticatedUserId
import io.ktor.server.auth.authenticate
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

fun Route.feedRoutes(service: FeedService) {
    authenticate("auth-jwt") {
        get("/api/v1/feed") {
            call.respond(
                service.feed(
                    viewerId = call.authenticatedUserId(),
                    rawCursor = call.request.queryParameters["cursor"],
                    requestedLimit = call.request.queryParameters["limit"]?.toIntOrNull(),
                )
            )
        }
    }
}
