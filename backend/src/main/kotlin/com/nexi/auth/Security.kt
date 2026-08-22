package com.nexi.auth

import com.auth0.jwt.JWT
import com.auth0.jwt.JWTVerifier
import com.auth0.jwt.algorithms.Algorithm
import com.nexi.config.JwtConfig
import de.mkammerer.argon2.Argon2Factory
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Clock
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Base64
import java.util.Date
import java.util.UUID

class PasswordHasher {
    private val argon2 = Argon2Factory.create()

    fun hash(password: String): String {
        val chars = password.toCharArray()
        return try {
            argon2.hash(3, 65_536, 1, chars)
        } finally {
            argon2.wipeArray(chars)
        }
    }

    fun verify(password: String, hash: String): Boolean {
        val chars = password.toCharArray()
        return try {
            argon2.verify(hash, chars)
        } finally {
            argon2.wipeArray(chars)
        }
    }
}

class TokenService(private val config: JwtConfig, private val clock: Clock = Clock.systemUTC()) {
    private val algorithm = Algorithm.HMAC256(config.secret)
    private val random = SecureRandom()

    val verifier: JWTVerifier = JWT.require(algorithm)
        .withIssuer(config.issuer)
        .withAudience(config.audience)
        .build()

    fun createAccessToken(userId: UUID): Pair<String, Instant> {
        val expiresAt = clock.instant().plus(config.accessTokenTtlMinutes, ChronoUnit.MINUTES)
        val token = JWT.create()
            .withIssuer(config.issuer)
            .withAudience(config.audience)
            .withSubject(userId.toString())
            .withClaim("type", "access")
            .withIssuedAt(Date.from(clock.instant()))
            .withExpiresAt(Date.from(expiresAt))
            .sign(algorithm)
        return token to expiresAt
    }

    fun createRefreshToken(): String = ByteArray(48).also(random::nextBytes).let {
        Base64.getUrlEncoder().withoutPadding().encodeToString(it)
    }

    fun createVerificationCode(): String = random.nextInt(1_000_000).toString().padStart(6, '0')

    fun hashOpaqueToken(token: String): String = MessageDigest.getInstance("SHA-256")
        .digest(token.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}
