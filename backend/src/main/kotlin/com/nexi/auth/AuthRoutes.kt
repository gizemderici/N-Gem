package com.nexi.auth

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import java.util.UUID

fun Route.authRoutes(service: AuthService, limiter: RequestRateLimiter) {
    route("/api/v1/auth") {
        post("/register") {
            limiter.check(call.rateLimitKey("register"))
            call.respond(HttpStatusCode.Created, service.register(call.receive<RegisterRequest>()))
        }

        post("/verify-email") {
            limiter.check(call.rateLimitKey("verify-email"))
            call.respond(service.verifyEmail(call.receive<VerifyEmailRequest>()))
        }

        post("/resend-verification") {
            limiter.check(call.rateLimitKey("resend-verification"))
            call.respond(HttpStatusCode.Accepted, service.resendVerification(call.receive<ResendVerificationRequest>()))
        }

        post("/login") {
            limiter.check(call.rateLimitKey("login"))
            call.respond(service.login(call.receive<LoginRequest>()))
        }

        post("/refresh") {
            limiter.check(call.rateLimitKey("refresh"))
            call.respond(service.refresh(call.receive<RefreshRequest>()))
        }

        post("/forgot-password") {
            limiter.check(call.rateLimitKey("forgot-password"))
            call.respond(HttpStatusCode.Accepted, service.forgotPassword(call.receive<ForgotPasswordRequest>()))
        }

        post("/reset-password") {
            limiter.check(call.rateLimitKey("reset-password"))
            service.resetPassword(call.receive<ResetPasswordRequest>())
            call.respond(MessageResponse("Şifren yenilendi. Tüm açık oturumlar kapatıldı."))
        }

        post("/logout") {
            service.logout(call.receive<LogoutRequest>())
            call.respond(MessageResponse("Oturum kapatıldı."))
        }
    }

    authenticate("auth-jwt") {
        get("/api/v1/users/me") {
            call.respond(service.getUser(call.authenticatedUserId()))
        }
    }
}

private fun ApplicationCall.rateLimitKey(action: String): String {
    return "$action:${request.local.remoteAddress}"
}

internal fun ApplicationCall.authenticatedUserId(): UUID {
    val subject = principal<JWTPrincipal>()?.payload?.subject
        ?: throw ApiException(HttpStatusCode.Unauthorized, "UNAUTHORIZED", "Oturum gerekli.")
    return runCatching { UUID.fromString(subject) }.getOrElse {
        throw ApiException(HttpStatusCode.Unauthorized, "INVALID_ACCESS_TOKEN", "Oturum geçersiz.")
    }
}
