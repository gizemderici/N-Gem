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

fun Route.authRoutes(service: AuthService, throttle: AuthThrottle) {
    route("/api/v1/auth") {
        post("/register") {
            throttle.checkAddress(call, "register")
            call.respond(HttpStatusCode.Created, service.register(call.receive<RegisterRequest>()))
        }

        // Kod deneme uçlarında adres sınırının yanında hesap sınırı da var:
        // altı haneli bir kodu farklı adreslerden denemek aksi halde serbest kalırdı.
        post("/verify-email") {
            throttle.checkAddress(call, "verify-email")
            val request = call.receive<VerifyEmailRequest>()
            throttle.checkAccount("verify-email", request.email)
            call.respond(service.verifyEmail(request))
        }

        post("/resend-verification") {
            throttle.checkAddress(call, "resend-verification")
            val request = call.receive<ResendVerificationRequest>()
            throttle.checkAccount("resend-verification", request.email)
            call.respond(HttpStatusCode.Accepted, service.resendVerification(request))
        }

        post("/login") {
            throttle.checkAddress(call, "login")
            val request = call.receive<LoginRequest>()
            throttle.checkAccount("login", request.email)
            call.respond(service.login(request))
        }

        post("/refresh") {
            throttle.checkAddress(call, "refresh")
            call.respond(service.refresh(call.receive<RefreshRequest>()))
        }

        post("/forgot-password") {
            throttle.checkAddress(call, "forgot-password")
            val request = call.receive<ForgotPasswordRequest>()
            throttle.checkAccount("forgot-password", request.email)
            call.respond(HttpStatusCode.Accepted, service.forgotPassword(request))
        }

        post("/reset-password") {
            throttle.checkAddress(call, "reset-password")
            val request = call.receive<ResetPasswordRequest>()
            throttle.checkAccount("reset-password", request.email)
            service.resetPassword(request)
            call.respond(MessageResponse("Şifren yenilendi. Tüm açık oturumlar kapatıldı."))
        }

        post("/logout") {
            throttle.checkAddress(call, "logout")
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

internal fun ApplicationCall.authenticatedUserId(): UUID {
    val subject = principal<JWTPrincipal>()?.payload?.subject
        ?: throw ApiException(HttpStatusCode.Unauthorized, "UNAUTHORIZED", "Oturum gerekli.")
    return runCatching { UUID.fromString(subject) }.getOrElse {
        throw ApiException(HttpStatusCode.Unauthorized, "INVALID_ACCESS_TOKEN", "Oturum geçersiz.")
    }
}
