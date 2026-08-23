package com.nexi.auth

import io.ktor.http.HttpStatusCode
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class RequestRateLimiterTest {
    /** Testin zamanı ileri sarabilmesi için sabit değil, elle ilerletilen saat. */
    private class MovableClock(var current: Instant) : Clock() {
        override fun instant(): Instant = current
        override fun getZone(): ZoneOffset = ZoneOffset.UTC
        override fun withZone(zone: java.time.ZoneId?): Clock = this
        fun advance(millis: Long) { current = current.plusMillis(millis) }
    }

    private val clock = MovableClock(Instant.parse("2026-08-23T00:00:00Z"))

    @Test
    fun `blocks once the window budget is spent`() {
        val limiter = RequestRateLimiter(maximumAttempts = 3, windowMillis = 60_000, clock = clock)

        repeat(3) { limiter.check("login:1.2.3.4") }

        val error = assertFailsWith<ApiException> { limiter.check("login:1.2.3.4") }
        assertEquals(HttpStatusCode.TooManyRequests, error.status)
        assertEquals("RATE_LIMITED", error.code)
    }

    @Test
    fun `keys are independent and the budget refills after the window`() {
        val limiter = RequestRateLimiter(maximumAttempts = 1, windowMillis = 60_000, clock = clock)

        limiter.check("login:1.2.3.4")
        limiter.check("login:5.6.7.8")
        assertFailsWith<ApiException> { limiter.check("login:1.2.3.4") }

        clock.advance(60_000)
        limiter.check("login:1.2.3.4")
    }

    @Test
    fun `expired windows are swept instead of accumulating forever`() {
        val limiter = RequestRateLimiter(maximumAttempts = 5, windowMillis = 60_000, clock = clock)

        repeat(50) { limiter.check("login:10.0.0.$it") }
        assertEquals(50, limiter.trackedKeyCount())

        // Pencere geçtikten sonraki ilk istek eskileri süpürmeli; geriye yalnızca
        // yeni anahtar kalır. Bu olmadan harita sonsuza kadar büyürdü.
        clock.advance(60_001)
        limiter.check("login:203.0.113.9")

        assertEquals(1, limiter.trackedKeyCount())
    }
}
