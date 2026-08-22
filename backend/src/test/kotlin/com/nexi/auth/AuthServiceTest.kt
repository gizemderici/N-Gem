package com.nexi.auth

import com.nexi.config.AppConfig
import com.nexi.config.DatabaseConfig
import com.nexi.config.JwtConfig
import com.nexi.config.StorageConfig
import io.ktor.http.HttpStatusCode
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class AuthServiceTest {
    private val clock = Clock.fixed(Instant.parse("2026-08-23T00:00:00Z"), ZoneOffset.UTC)
    private val repository = InMemoryAuthRepository()
    private val config = AppConfig(
        environment = "test",
        database = DatabaseConfig("unused", "unused", "unused"),
        jwt = JwtConfig(
            secret = "test-secret-that-is-long-enough-for-hmac",
            issuer = "nexi-test",
            audience = "nexi-mobile",
            accessTokenTtlMinutes = 15,
            refreshTokenTtlDays = 30,
        ),
        storage = StorageConfig(
            endpoint = "http://localhost:9000",
            publicEndpoint = "http://localhost:9000",
            region = "us-east-1",
            accessKey = "test",
            secretKey = "test-secret",
            bucket = "test-media",
            maxImageSizeBytes = 10 * 1024 * 1024,
        ),
        verificationCodeTtlMinutes = 10,
        exposeDevelopmentCodes = true,
    )
    private val service = AuthService(repository, PasswordHasher(), TokenService(config.jwt, clock), config, clock)

    @Test
    fun `registration verification login and rotating refresh work together`() {
        val registration = register()
        val code = assertNotNull(registration.developmentCode)
        assertTrue(registration.verificationRequired)

        val blockedLogin = assertFailsWith<ApiException> {
            service.login(LoginRequest("gizem@example.com", "StrongPass123"))
        }
        assertEquals(HttpStatusCode.Forbidden, blockedLogin.status)
        assertEquals("EMAIL_NOT_VERIFIED", blockedLogin.code)

        val verified = service.verifyEmail(VerifyEmailRequest("gizem@example.com", code))
        assertTrue(verified.user.emailVerified)
        assertTrue(verified.tokens.accessToken.isNotBlank())

        val login = service.login(LoginRequest("GIZEM@EXAMPLE.COM", "StrongPass123"))
        val rotated = service.refresh(RefreshRequest(login.tokens.refreshToken))
        assertNotEquals(login.tokens.refreshToken, rotated.refreshToken)

        val reusedToken = assertFailsWith<ApiException> {
            service.refresh(RefreshRequest(login.tokens.refreshToken))
        }
        assertEquals("INVALID_REFRESH_TOKEN", reusedToken.code)

        service.logout(LogoutRequest(rotated.refreshToken))
        assertFailsWith<ApiException> { service.refresh(RefreshRequest(rotated.refreshToken)) }
    }

    @Test
    fun `password reset revokes existing sessions and accepts new password`() {
        val registration = register()
        service.verifyEmail(VerifyEmailRequest("gizem@example.com", assertNotNull(registration.developmentCode)))
        val existingSession = service.login(LoginRequest("gizem@example.com", "StrongPass123"))

        val reset = service.forgotPassword(ForgotPasswordRequest("gizem@example.com"))
        service.resetPassword(
            ResetPasswordRequest("gizem@example.com", assertNotNull(reset.developmentCode), "NewPassword456")
        )

        assertFailsWith<ApiException> { service.refresh(RefreshRequest(existingSession.tokens.refreshToken)) }
        assertFailsWith<ApiException> { service.login(LoginRequest("gizem@example.com", "StrongPass123")) }
        assertTrue(service.login(LoginRequest("gizem@example.com", "NewPassword456")).tokens.accessToken.isNotBlank())
    }

    @Test
    fun `duplicate email is rejected regardless of letter case`() {
        register()
        val exception = assertFailsWith<ApiException> {
            service.register(
                RegisterRequest("Başka Kullanıcı", "baska", "GIZEM@EXAMPLE.COM", "StrongPass123", true)
            )
        }
        assertEquals(HttpStatusCode.Conflict, exception.status)
        assertEquals("EMAIL_ALREADY_USED", exception.code)
    }

    private fun register() = service.register(
        RegisterRequest(
            fullName = "Gizem Derici",
            username = "gizem",
            email = "gizem@example.com",
            password = "StrongPass123",
            acceptedTerms = true,
        )
    )
}
