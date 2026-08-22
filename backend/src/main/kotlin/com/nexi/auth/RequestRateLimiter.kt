package com.nexi.auth

import io.ktor.http.HttpStatusCode
import java.time.Clock
import java.util.concurrent.ConcurrentHashMap

class RequestRateLimiter(
    private val maximumAttempts: Int = 10,
    private val windowMillis: Long = 60_000,
    private val clock: Clock = Clock.systemUTC(),
) {
    private data class Window(val startedAt: Long, val count: Int)
    private val windows = ConcurrentHashMap<String, Window>()

    fun check(key: String) {
        val now = clock.millis()
        val result = windows.compute(key) { _, previous ->
            if (previous == null || now - previous.startedAt >= windowMillis) Window(now, 1)
            else previous.copy(count = previous.count + 1)
        }!!
        if (result.count > maximumAttempts) {
            throw ApiException(HttpStatusCode.TooManyRequests, "RATE_LIMITED", "Çok fazla deneme yaptın. Lütfen biraz sonra tekrar dene.")
        }
    }
}
