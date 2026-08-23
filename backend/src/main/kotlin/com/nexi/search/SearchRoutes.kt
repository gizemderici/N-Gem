package com.nexi.search

import com.nexi.auth.RequestRateLimiter
import com.nexi.auth.authenticatedUserId
import io.ktor.server.auth.authenticate
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route

/**
 * Arama uçları hız sınırlıdır: tam metin sorguları diğer uçlardan pahalı ve
 * kolayca kötüye kullanılabiliyor.
 */
fun Route.searchRoutes(service: SearchService, limiter: RequestRateLimiter) {
    authenticate("auth-jwt") {
        route("/api/v1/search") {
            get {
                val viewerId = call.authenticatedUserId()
                limiter.check("search:$viewerId")
                call.respond(service.searchAll(viewerId, call.request.queryParameters["q"]))
            }

            get("/users") {
                val viewerId = call.authenticatedUserId()
                limiter.check("search:$viewerId")
                call.respond(
                    service.searchUsers(
                        viewerId = viewerId,
                        rawQuery = call.request.queryParameters["q"],
                        rawCursor = call.request.queryParameters["cursor"],
                        requestedLimit = call.request.queryParameters["limit"]?.toIntOrNull(),
                    )
                )
            }

            get("/posts") {
                val viewerId = call.authenticatedUserId()
                limiter.check("search:$viewerId")
                call.respond(
                    service.searchPosts(
                        viewerId = viewerId,
                        rawQuery = call.request.queryParameters["q"],
                        rawCursor = call.request.queryParameters["cursor"],
                        requestedLimit = call.request.queryParameters["limit"]?.toIntOrNull(),
                    )
                )
            }
        }

        get("/api/v1/explore") {
            call.respond(
                service.explore(
                    viewerId = call.authenticatedUserId(),
                    rawCursor = call.request.queryParameters["cursor"],
                    requestedLimit = call.request.queryParameters["limit"]?.toIntOrNull(),
                )
            )
        }
    }
}
