package com.nexi.auth

import kotlinx.serialization.Serializable
import java.time.Instant
import java.util.UUID

data class User(
    val id: UUID,
    val fullName: String,
    val username: String,
    val email: String,
    val passwordHash: String,
    val emailVerifiedAt: Instant?,
    val createdAt: Instant,
    val updatedAt: Instant,
)

enum class VerificationPurpose { EMAIL_VERIFICATION, PASSWORD_RESET }

data class VerificationCode(
    val id: UUID,
    val userId: UUID,
    val purpose: VerificationPurpose,
    val codeHash: String,
    val expiresAt: Instant,
    val consumedAt: Instant?,
    val attemptCount: Int,
    val createdAt: Instant,
)

@Serializable
data class RegisterRequest(
    val fullName: String,
    val username: String,
    val email: String,
    val password: String,
    val acceptedTerms: Boolean,
)

@Serializable data class LoginRequest(val email: String, val password: String)
@Serializable data class VerifyEmailRequest(val email: String, val code: String)
@Serializable data class ResendVerificationRequest(val email: String)
@Serializable data class RefreshRequest(val refreshToken: String)
@Serializable data class LogoutRequest(val refreshToken: String)
@Serializable data class ForgotPasswordRequest(val email: String)
@Serializable data class ResetPasswordRequest(val email: String, val code: String, val newPassword: String)

@Serializable
data class UserResponse(
    val id: String,
    val fullName: String,
    val username: String,
    val email: String,
    val emailVerified: Boolean,
    val createdAt: String,
)

@Serializable
data class TokenResponse(
    val accessToken: String,
    val refreshToken: String,
    val tokenType: String = "Bearer",
    val expiresInSeconds: Long,
)

@Serializable data class AuthResponse(val user: UserResponse, val tokens: TokenResponse)

@Serializable
data class RegistrationResponse(
    val user: UserResponse,
    val verificationRequired: Boolean = true,
    val developmentCode: String? = null,
)

@Serializable data class VerificationDispatchResponse(val accepted: Boolean = true, val developmentCode: String? = null)
@Serializable data class MessageResponse(val message: String)
@Serializable data class ErrorResponse(val code: String, val message: String, val field: String? = null)

fun User.toResponse() = UserResponse(
    id = id.toString(),
    fullName = fullName,
    username = username,
    email = email,
    emailVerified = emailVerifiedAt != null,
    createdAt = createdAt.toString(),
)
