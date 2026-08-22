package com.nexi.auth

import java.time.Instant
import java.util.UUID

interface AuthRepository {
    fun findUserByEmail(normalizedEmail: String): User?
    fun findUserByUsername(normalizedUsername: String): User?
    fun findUserById(id: UUID): User?
    fun createUserWithVerification(user: User, verificationCode: VerificationCode)
    fun saveVerificationCode(code: VerificationCode)
    fun findLatestVerificationCode(userId: UUID, purpose: VerificationPurpose): VerificationCode?
    fun incrementVerificationAttempt(id: UUID)
    fun consumeVerificationCode(id: UUID, consumedAt: Instant): Boolean
    fun markEmailVerified(userId: UUID, verifiedAt: Instant)
    fun updatePassword(userId: UUID, passwordHash: String, updatedAt: Instant)
    fun createRefreshSession(id: UUID, userId: UUID, tokenHash: String, expiresAt: Instant, createdAt: Instant)
    fun takeRefreshSession(tokenHash: String, revokedAt: Instant): UUID?
    fun revokeRefreshSession(tokenHash: String, revokedAt: Instant)
    fun revokeAllRefreshSessions(userId: UUID, revokedAt: Instant)
}
