package com.nexi.auth

import java.sql.Connection
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

class JdbcAuthRepository(private val dataSource: DataSource) : AuthRepository {
    override fun findUserByEmail(normalizedEmail: String): User? = dataSource.connection.use { connection ->
        connection.prepareStatement("SELECT * FROM users WHERE email_normalized = ?").use { statement ->
            statement.setString(1, normalizedEmail)
            statement.executeQuery().use { results -> if (results.next()) results.toUser() else null }
        }
    }

    override fun findUserByUsername(normalizedUsername: String): User? = dataSource.connection.use { connection ->
        connection.prepareStatement("SELECT * FROM users WHERE username_normalized = ?").use { statement ->
            statement.setString(1, normalizedUsername)
            statement.executeQuery().use { results -> if (results.next()) results.toUser() else null }
        }
    }

    override fun findUserById(id: UUID): User? = dataSource.connection.use { connection ->
        connection.prepareStatement("SELECT * FROM users WHERE id = ?").use { statement ->
            statement.setObject(1, id)
            statement.executeQuery().use { results -> if (results.next()) results.toUser() else null }
        }
    }

    override fun createUserWithVerification(user: User, verificationCode: VerificationCode) {
        dataSource.connection.use { connection ->
            connection.autoCommit = false
            try {
                connection.prepareStatement(
                    """INSERT INTO users
                       (id, full_name, username, username_normalized, email, email_normalized,
                        password_hash, email_verified_at, created_at, updated_at)
                       VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"""
                ).use { statement ->
                    statement.setObject(1, user.id)
                    statement.setString(2, user.fullName)
                    statement.setString(3, user.username)
                    statement.setString(4, normalizeUsername(user.username))
                    statement.setString(5, user.email)
                    statement.setString(6, normalizeEmail(user.email))
                    statement.setString(7, user.passwordHash)
                    statement.setTimestamp(8, user.emailVerifiedAt?.let(Timestamp::from))
                    statement.setTimestamp(9, Timestamp.from(user.createdAt))
                    statement.setTimestamp(10, Timestamp.from(user.updatedAt))
                    statement.executeUpdate()
                }
                connection.insertVerificationCode(verificationCode)
                connection.commit()
            } catch (exception: SQLException) {
                connection.rollback()
                throw exception
            } finally {
                connection.autoCommit = true
            }
        }
    }

    override fun saveVerificationCode(code: VerificationCode) {
        dataSource.connection.use { it.insertVerificationCode(code) }
    }

    override fun findLatestVerificationCode(userId: UUID, purpose: VerificationPurpose): VerificationCode? =
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """SELECT * FROM verification_codes
                   WHERE user_id = ? AND purpose = ? AND consumed_at IS NULL
                   ORDER BY created_at DESC LIMIT 1"""
            ).use { statement ->
                statement.setObject(1, userId)
                statement.setString(2, purpose.name)
                statement.executeQuery().use { results -> if (results.next()) results.toVerificationCode() else null }
            }
        }

    override fun incrementVerificationAttempt(id: UUID) {
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                "UPDATE verification_codes SET attempt_count = attempt_count + 1 WHERE id = ?"
            ).use { statement ->
                statement.setObject(1, id)
                statement.executeUpdate()
            }
        }
    }

    override fun consumeVerificationCode(id: UUID, consumedAt: Instant): Boolean = dataSource.connection.use { connection ->
        connection.prepareStatement(
            "UPDATE verification_codes SET consumed_at = ? WHERE id = ? AND consumed_at IS NULL"
        ).use { statement ->
            statement.setTimestamp(1, Timestamp.from(consumedAt))
            statement.setObject(2, id)
            statement.executeUpdate() == 1
        }
    }

    override fun markEmailVerified(userId: UUID, verifiedAt: Instant) {
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                "UPDATE users SET email_verified_at = ?, updated_at = ? WHERE id = ?"
            ).use { statement ->
                statement.setTimestamp(1, Timestamp.from(verifiedAt))
                statement.setTimestamp(2, Timestamp.from(verifiedAt))
                statement.setObject(3, userId)
                statement.executeUpdate()
            }
        }
    }

    override fun updatePassword(userId: UUID, passwordHash: String, updatedAt: Instant) {
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                "UPDATE users SET password_hash = ?, updated_at = ? WHERE id = ?"
            ).use { statement ->
                statement.setString(1, passwordHash)
                statement.setTimestamp(2, Timestamp.from(updatedAt))
                statement.setObject(3, userId)
                statement.executeUpdate()
            }
        }
    }

    override fun createRefreshSession(
        id: UUID,
        userId: UUID,
        tokenHash: String,
        expiresAt: Instant,
        createdAt: Instant,
    ) {
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """INSERT INTO refresh_sessions
                   (id, user_id, token_hash, expires_at, created_at) VALUES (?, ?, ?, ?, ?)"""
            ).use { statement ->
                statement.setObject(1, id)
                statement.setObject(2, userId)
                statement.setString(3, tokenHash)
                statement.setTimestamp(4, Timestamp.from(expiresAt))
                statement.setTimestamp(5, Timestamp.from(createdAt))
                statement.executeUpdate()
            }
        }
    }

    override fun takeRefreshSession(tokenHash: String, revokedAt: Instant): UUID? = dataSource.connection.use { connection ->
        connection.prepareStatement(
            """UPDATE refresh_sessions SET revoked_at = ?
               WHERE token_hash = ? AND revoked_at IS NULL AND expires_at > ?
               RETURNING user_id"""
        ).use { statement ->
            statement.setTimestamp(1, Timestamp.from(revokedAt))
            statement.setString(2, tokenHash)
            statement.setTimestamp(3, Timestamp.from(revokedAt))
            statement.executeQuery().use { results -> if (results.next()) results.getObject("user_id", UUID::class.java) else null }
        }
    }

    override fun revokeRefreshSession(tokenHash: String, revokedAt: Instant) {
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                "UPDATE refresh_sessions SET revoked_at = ? WHERE token_hash = ? AND revoked_at IS NULL"
            ).use { statement ->
                statement.setTimestamp(1, Timestamp.from(revokedAt))
                statement.setString(2, tokenHash)
                statement.executeUpdate()
            }
        }
    }

    override fun revokeAllRefreshSessions(userId: UUID, revokedAt: Instant) {
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                "UPDATE refresh_sessions SET revoked_at = ? WHERE user_id = ? AND revoked_at IS NULL"
            ).use { statement ->
                statement.setTimestamp(1, Timestamp.from(revokedAt))
                statement.setObject(2, userId)
                statement.executeUpdate()
            }
        }
    }

    private fun Connection.insertVerificationCode(code: VerificationCode) {
        prepareStatement(
            """INSERT INTO verification_codes
               (id, user_id, purpose, code_hash, expires_at, consumed_at, attempt_count, created_at)
               VALUES (?, ?, ?, ?, ?, ?, ?, ?)"""
        ).use { statement ->
            statement.setObject(1, code.id)
            statement.setObject(2, code.userId)
            statement.setString(3, code.purpose.name)
            statement.setString(4, code.codeHash)
            statement.setTimestamp(5, Timestamp.from(code.expiresAt))
            statement.setTimestamp(6, code.consumedAt?.let(Timestamp::from))
            statement.setInt(7, code.attemptCount)
            statement.setTimestamp(8, Timestamp.from(code.createdAt))
            statement.executeUpdate()
        }
    }

    private fun ResultSet.toUser() = User(
        id = getObject("id", UUID::class.java),
        fullName = getString("full_name"),
        username = getString("username"),
        email = getString("email"),
        passwordHash = getString("password_hash"),
        emailVerifiedAt = getTimestamp("email_verified_at")?.toInstant(),
        createdAt = getTimestamp("created_at").toInstant(),
        updatedAt = getTimestamp("updated_at").toInstant(),
    )

    private fun ResultSet.toVerificationCode() = VerificationCode(
        id = getObject("id", UUID::class.java),
        userId = getObject("user_id", UUID::class.java),
        purpose = VerificationPurpose.valueOf(getString("purpose")),
        codeHash = getString("code_hash"),
        expiresAt = getTimestamp("expires_at").toInstant(),
        consumedAt = getTimestamp("consumed_at")?.toInstant(),
        attemptCount = getInt("attempt_count"),
        createdAt = getTimestamp("created_at").toInstant(),
    )
}

internal fun normalizeEmail(value: String) = value.trim().lowercase()
internal fun normalizeUsername(value: String) = value.trim().lowercase()
