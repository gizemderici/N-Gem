package com.nexi.recommendations

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

fun Route.recommendationRoutes(service: RecommendationService) {
    authenticate("auth-jwt") {
        route("/api/v1/recommendations") {
            post("/events") {
                call.respond(
                    HttpStatusCode.Accepted,
                    service.append(call.authenticatedUserId(), call.receive<RecommendationEventBatchRequest>())
                )
            }
            get("/profile") {
                val hour = call.request.queryParameters["localHour"]?.toIntOrNull()
                call.respond(service.profile(call.authenticatedUserId(), hour))
            }
            delete("/profile") {
                call.respond(service.reset(call.authenticatedUserId()))
            }
        }
    }
}
