package com.nexi.profiles

import com.nexi.auth.ApiException
import com.nexi.auth.authenticatedUserId
import com.nexi.posts.PostService
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.put
import io.ktor.server.routing.route

/**
 * Kullanıcılar yol içinde **kullanıcı adıyla** anılıyor; kimlikler yalnızca
 * cevaplarda geçiyor. `/users/me` ile çakışma yok: kullanıcı adı en az üç
 * karakter olmak zorunda, yani kimse "me" adını alamıyor.
 */
fun Route.profileRoutes(service: ProfileService, postService: PostService) {
    authenticate("auth-jwt") {
        // Kendi profilini düzenleme. `{username}` desenli yollardan önce
        // tanımlanıyor; Ktor literal segmenti zaten öncelikli tutuyor ama
        // sıralamayı da okunur bırakıyoruz.
        route("/api/v1/users/me") {
            patch("/profile") {
                val userId = call.authenticatedUserId()
                call.respond(
                    service.updateProfile(userId, service.username(userId), call.receive<UpdateProfileRequest>())
                )
            }

            put("/avatar") {
                val userId = call.authenticatedUserId()
                call.respond(service.setAvatar(userId, service.username(userId), call.receive<SetAvatarRequest>()))
            }

            delete("/avatar") {
                val userId = call.authenticatedUserId()
                call.respond(service.removeAvatar(userId, service.username(userId)))
            }
        }

        route("/api/v1/users/{username}") {
            get {
                call.respond(service.profile(call.authenticatedUserId(), call.username()))
            }

            // Profilin altındaki gönderi listesi. `/users/me/posts` ile aynı
            // gövdeyi döner; orası kısayol, burası genel hali.
            get("/posts") {
                val viewerId = call.authenticatedUserId()
                call.respond(
                    postService.postsByOwner(
                        ownerId = service.userId(call.username()),
                        viewerId = viewerId,
                        rawCursor = call.request.queryParameters["cursor"],
                        requestedLimit = call.request.queryParameters["limit"]?.toIntOrNull(),
                    )
                )
            }

            put("/follow") {
                call.respond(service.setFollow(call.authenticatedUserId(), call.username(), true))
            }

            delete("/follow") {
                call.respond(service.setFollow(call.authenticatedUserId(), call.username(), false))
            }

            get("/followers") {
                call.respond(
                    service.followers(
                        viewerId = call.authenticatedUserId(),
                        username = call.username(),
                        rawCursor = call.request.queryParameters["cursor"],
                        requestedLimit = call.request.queryParameters["limit"]?.toIntOrNull(),
                    )
                )
            }

            get("/following") {
                call.respond(
                    service.following(
                        viewerId = call.authenticatedUserId(),
                        username = call.username(),
                        rawCursor = call.request.queryParameters["cursor"],
                        requestedLimit = call.request.queryParameters["limit"]?.toIntOrNull(),
                    )
                )
            }
        }
    }
}

private fun ApplicationCall.username(): String = parameters["username"]?.takeIf { it.isNotBlank() }
    ?: throw ApiException(HttpStatusCode.BadRequest, "INVALID_USERNAME", "Kullanıcı adı geçersiz.", "username")
