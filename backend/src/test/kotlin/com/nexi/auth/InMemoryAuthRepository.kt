package com.nexi.auth

import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

internal class InMemoryAuthRepository : AuthRepository {
    private val users = ConcurrentHashMap<UUID, User>()
    private val codes = ConcurrentHashMap<UUID, VerificationCode>()
    private val sessions = ConcurrentHashMap<String, Session>()

    private data class Session(val userId: UUID, val expiresAt: Instant, var revokedAt: Instant? = null)

    override fun findUserByEmail(normalizedEmail: String) = users.values.find { normalizeEmail(it.email) == normalizedEmail }
    override fun findUserByUsername(normalizedUsername: String) = users.values.find { normalizeUsername(it.username) == normalizedUsername }
    override fun findUserById(id: UUID) = users[id]

    override fun createUserWithVerification(user: User, verificationCode: VerificationCode) {
        users[user.id] = user
        codes[verificationCode.id] = verificationCode
    }

    override fun saveVerificationCode(code: VerificationCode) { codes[code.id] = code }

    override fun findLatestVerificationCode(userId: UUID, purpose: VerificationPurpose) = codes.values
        .filter { it.userId == userId && it.purpose == purpose && it.consumedAt == null }
        .maxByOrNull { it.createdAt }

    override fun incrementVerificationAttempt(id: UUID) {
        codes.computeIfPresent(id) { _, code -> code.copy(attemptCount = code.attemptCount + 1) }
    }

    override fun consumeVerificationCode(id: UUID, consumedAt: Instant): Boolean {
        var consumed = false
        codes.computeIfPresent(id) { _, code ->
            if (code.consumedAt == null) {
                consumed = true
                code.copy(consumedAt = consumedAt)
            } else code
        }
        return consumed
    }

    override fun markEmailVerified(userId: UUID, verifiedAt: Instant) {
        users.computeIfPresent(userId) { _, user -> user.copy(emailVerifiedAt = verifiedAt, updatedAt = verifiedAt) }
    }

    override fun updatePassword(userId: UUID, passwordHash: String, updatedAt: Instant) {
        users.computeIfPresent(userId) { _, user -> user.copy(passwordHash = passwordHash, updatedAt = updatedAt) }
    }

    override fun createRefreshSession(
        id: UUID,
        userId: UUID,
        tokenHash: String,
        expiresAt: Instant,
        createdAt: Instant,
    ) {
        sessions[tokenHash] = Session(userId, expiresAt)
    }

    override fun takeRefreshSession(tokenHash: String, revokedAt: Instant): UUID? {
        val session = sessions[tokenHash] ?: return null
        synchronized(session) {
            if (session.revokedAt != null || session.expiresAt <= revokedAt) return null
            session.revokedAt = revokedAt
            return session.userId
        }
    }

    override fun revokeRefreshSession(tokenHash: String, revokedAt: Instant) {
        sessions[tokenHash]?.revokedAt = revokedAt
    }

    override fun revokeAllRefreshSessions(userId: UUID, revokedAt: Instant) {
        sessions.values.filter { it.userId == userId }.forEach { it.revokedAt = revokedAt }
    }
}
