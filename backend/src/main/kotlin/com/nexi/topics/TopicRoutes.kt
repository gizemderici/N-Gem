package com.nexi.topics

import com.nexi.auth.authenticatedUserId
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.put
import io.ktor.server.routing.route

fun Route.topicRoutes(service: TopicService) {
    // Katalog herkese açık: kayıt ekranında da ilgi alanları gösterilebilsin.
    get("/api/v1/topics") {
        call.respond(service.list())
    }

    authenticate("auth-jwt") {
        route("/api/v1/users/me/topics") {
            get {
                call.respond(service.userTopics(call.authenticatedUserId()))
            }
            put {
                call.respond(service.replace(call.authenticatedUserId(), call.receive<UpdateUserTopicsRequest>()))
            }
        }
    }
}
