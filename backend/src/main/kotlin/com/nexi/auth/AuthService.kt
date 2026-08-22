package com.nexi.auth

import com.nexi.config.AppConfig
import io.ktor.http.HttpStatusCode
import org.slf4j.LoggerFactory
import java.sql.SQLException
import java.time.Clock
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

class AuthService(
    private val repository: AuthRepository,
    private val passwordHasher: PasswordHasher,
    private val tokenService: TokenService,
    private val config: AppConfig,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val logger = LoggerFactory.getLogger(AuthService::class.java)

    fun register(request: RegisterRequest): RegistrationResponse {
        val fullName = request.fullName.trim().replace(Regex("\\s+"), " ")
        val username = request.username.trim()
        val email = normalizeEmail(request.email)
        validateRegistration(fullName, username, email, request.password, request.acceptedTerms)

        if (repository.findUserByEmail(email) != null) {
            throw ApiException(HttpStatusCode.Conflict, "EMAIL_ALREADY_USED", "Bu e-posta adresi zaten kullanılıyor.", "email")
        }
        if (repository.findUserByUsername(normalizeUsername(username)) != null) {
            throw ApiException(HttpStatusCode.Conflict, "USERNAME_ALREADY_USED", "Bu kullanıcı adı zaten kullanılıyor.", "username")
        }

        val now = clock.instant()
        val user = User(
            id = UUID.randomUUID(),
            fullName = fullName,
            username = username,
            email = email,
            passwordHash = passwordHasher.hash(request.password),
            emailVerifiedAt = null,
            createdAt = now,
            updatedAt = now,
        )
        val (verification, rawCode) = createCode(user.id, VerificationPurpose.EMAIL_VERIFICATION, now)

        try {
            repository.createUserWithVerification(user, verification)
        } catch (exception: SQLException) {
            if (exception.sqlState == "23505") {
                throw ApiException(HttpStatusCode.Conflict, "ACCOUNT_ALREADY_EXISTS", "E-posta veya kullanıcı adı zaten kullanılıyor.")
            }
            throw exception
        }

        dispatchCode(user.email, rawCode, VerificationPurpose.EMAIL_VERIFICATION)
        return RegistrationResponse(user.toResponse(), developmentCode = developmentCode(rawCode))
    }

    fun verifyEmail(request: VerifyEmailRequest): AuthResponse {
        val user = repository.findUserByEmail(normalizeEmail(request.email))
            ?: throw invalidCode()
        verifyAndConsumeCode(user, VerificationPurpose.EMAIL_VERIFICATION, request.code)
        val now = clock.instant()
        repository.markEmailVerified(user.id, now)
        return issueAuthResponse(user.copy(emailVerifiedAt = now, updatedAt = now))
    }

    fun resendVerification(request: ResendVerificationRequest): VerificationDispatchResponse {
        val user = repository.findUserByEmail(normalizeEmail(request.email))
            ?: return VerificationDispatchResponse()
        if (user.emailVerifiedAt != null) return VerificationDispatchResponse()

        val (verification, rawCode) = createCode(user.id, VerificationPurpose.EMAIL_VERIFICATION, clock.instant())
        repository.saveVerificationCode(verification)
        dispatchCode(user.email, rawCode, VerificationPurpose.EMAIL_VERIFICATION)
        return VerificationDispatchResponse(developmentCode = developmentCode(rawCode))
    }

    fun login(request: LoginRequest): AuthResponse {
        val user = repository.findUserByEmail(normalizeEmail(request.email))
        if (user == null || !passwordHasher.verify(request.password, user.passwordHash)) {
            throw ApiException(HttpStatusCode.Unauthorized, "INVALID_CREDENTIALS", "E-posta veya şifre hatalı.")
        }
        if (user.emailVerifiedAt == null) {
            throw ApiException(HttpStatusCode.Forbidden, "EMAIL_NOT_VERIFIED", "Devam etmek için e-posta adresini doğrulamalısın.")
        }
        return issueAuthResponse(user)
    }

    fun refresh(request: RefreshRequest): TokenResponse {
        val now = clock.instant()
        val userId = repository.takeRefreshSession(tokenService.hashOpaqueToken(request.refreshToken), now)
            ?: throw ApiException(HttpStatusCode.Unauthorized, "INVALID_REFRESH_TOKEN", "Oturum geçersiz veya süresi dolmuş.")
        val user = repository.findUserById(userId)
            ?: throw ApiException(HttpStatusCode.Unauthorized, "INVALID_REFRESH_TOKEN", "Oturum geçersiz.")
        return issueTokens(user)
    }

    fun logout(request: LogoutRequest) {
        repository.revokeRefreshSession(tokenService.hashOpaqueToken(request.refreshToken), clock.instant())
    }

    fun forgotPassword(request: ForgotPasswordRequest): VerificationDispatchResponse {
        val user = repository.findUserByEmail(normalizeEmail(request.email))
            ?: return VerificationDispatchResponse()
        val (verification, rawCode) = createCode(user.id, VerificationPurpose.PASSWORD_RESET, clock.instant())
        repository.saveVerificationCode(verification)
        dispatchCode(user.email, rawCode, VerificationPurpose.PASSWORD_RESET)
        return VerificationDispatchResponse(developmentCode = developmentCode(rawCode))
    }

    fun resetPassword(request: ResetPasswordRequest) {
        validatePassword(request.newPassword)
        val user = repository.findUserByEmail(normalizeEmail(request.email)) ?: throw invalidCode()
        verifyAndConsumeCode(user, VerificationPurpose.PASSWORD_RESET, request.code)
        val now = clock.instant()
        repository.updatePassword(user.id, passwordHasher.hash(request.newPassword), now)
        repository.revokeAllRefreshSessions(user.id, now)
    }

    fun getUser(userId: UUID): UserResponse = repository.findUserById(userId)?.toResponse()
        ?: throw ApiException(HttpStatusCode.NotFound, "USER_NOT_FOUND", "Kullanıcı bulunamadı.")

    private fun issueAuthResponse(user: User) = AuthResponse(user.toResponse(), issueTokens(user))

    private fun issueTokens(user: User): TokenResponse {
        val now = clock.instant()
        val (accessToken, accessExpiresAt) = tokenService.createAccessToken(user.id)
        val refreshToken = tokenService.createRefreshToken()
        repository.createRefreshSession(
            id = UUID.randomUUID(),
            userId = user.id,
            tokenHash = tokenService.hashOpaqueToken(refreshToken),
            expiresAt = now.plus(config.jwt.refreshTokenTtlDays, ChronoUnit.DAYS),
            createdAt = now,
        )
        return TokenResponse(
            accessToken = accessToken,
            refreshToken = refreshToken,
            expiresInSeconds = ChronoUnit.SECONDS.between(now, accessExpiresAt),
        )
    }

    private fun createCode(userId: UUID, purpose: VerificationPurpose, now: Instant): Pair<VerificationCode, String> {
        val rawCode = tokenService.createVerificationCode()
        return VerificationCode(
            id = UUID.randomUUID(),
            userId = userId,
            purpose = purpose,
            codeHash = tokenService.hashOpaqueToken(rawCode),
            expiresAt = now.plus(config.verificationCodeTtlMinutes, ChronoUnit.MINUTES),
            consumedAt = null,
            attemptCount = 0,
            createdAt = now,
        ) to rawCode
    }

    private fun verifyAndConsumeCode(user: User, purpose: VerificationPurpose, rawCode: String) {
        if (!rawCode.matches(Regex("^\\d{6}$"))) throw invalidCode()
        val code = repository.findLatestVerificationCode(user.id, purpose) ?: throw invalidCode()
        val now = clock.instant()
        if (code.expiresAt <= now || code.attemptCount >= 5) throw invalidCode()
        if (code.codeHash != tokenService.hashOpaqueToken(rawCode)) {
            repository.incrementVerificationAttempt(code.id)
            throw invalidCode()
        }
        if (!repository.consumeVerificationCode(code.id, now)) throw invalidCode()
    }

    private fun validateRegistration(
        fullName: String,
        username: String,
        email: String,
        password: String,
        acceptedTerms: Boolean,
    ) {
        if (fullName.length !in 3..120) validation("INVALID_FULL_NAME", "Ad soyad 3-120 karakter olmalı.", "fullName").let { throw it }
        if (!username.matches(Regex("^[A-Za-z0-9._]{3,30}$"))) {
            throw validation("INVALID_USERNAME", "Kullanıcı adı 3-30 karakter olmalı; yalnızca harf, sayı, nokta ve alt çizgi içerebilir.", "username")
        }
        if (!email.matches(Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$") ) || email.length > 254) {
            throw validation("INVALID_EMAIL", "Geçerli bir e-posta adresi yazmalısın.", "email")
        }
        validatePassword(password)
        if (!acceptedTerms) throw validation("TERMS_NOT_ACCEPTED", "Devam etmek için koşulları kabul etmelisin.", "acceptedTerms")
    }

    private fun validatePassword(password: String) {
        if (password.length !in 8..128) {
            throw validation("INVALID_PASSWORD", "Şifre 8-128 karakter olmalı.", "password")
        }
    }

    private fun invalidCode() = ApiException(
        HttpStatusCode.UnprocessableEntity,
        "INVALID_VERIFICATION_CODE",
        "Doğrulama kodu geçersiz veya süresi dolmuş.",
        "code",
    )

    private fun developmentCode(rawCode: String) = if (config.exposeDevelopmentCodes) rawCode else null

    private fun dispatchCode(email: String, code: String, purpose: VerificationPurpose) {
        if (config.exposeDevelopmentCodes) {
            logger.info("Development verification code: email={}, purpose={}, code={}", email, purpose, code)
        } else {
            // Üretime geçmeden önce bu nokta gerçek e-posta sağlayıcısına bağlanmalıdır.
            logger.warn("Verification requested but email provider is not configured: purpose={}", purpose)
        }
    }
}
