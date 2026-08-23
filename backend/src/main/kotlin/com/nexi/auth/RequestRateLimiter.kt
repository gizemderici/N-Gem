package com.nexi.auth

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import java.time.Clock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

class RequestRateLimiter(
    private val maximumAttempts: Int = 10,
    private val windowMillis: Long = 60_000,
    private val clock: Clock = Clock.systemUTC(),
) {
    private data class Window(val startedAt: Long, val count: Int)

    private val windows = ConcurrentHashMap<String, Window>()
    private val lastSweepAt = AtomicLong(0)

    fun check(key: String) {
        val now = clock.millis()
        sweepExpired(now)

        val result = windows.compute(key) { _, previous ->
            if (previous == null || now - previous.startedAt >= windowMillis) Window(now, 1)
            else previous.copy(count = previous.count + 1)
        }!!

        if (result.count > maximumAttempts) {
            throw ApiException(
                HttpStatusCode.TooManyRequests,
                "RATE_LIMITED",
                "Çok fazla deneme yaptın. Lütfen biraz sonra tekrar dene.",
            )
        }
    }

    /** Süpürmenin gerçekten çalıştığını doğrulayabilmek için. */
    internal fun trackedKeyCount(): Int = windows.size

    /**
     * Süresi dolmuş pencereleri atar.
     *
     * Bu olmadan harita her IP+eylem çifti için kalıcı bir kayıt biriktirir ve
     * uzun süre ayakta kalan sunucuda bellek sızıntısına döner. Her istekte
     * taramak pahalı olurdu; pencere başına en fazla bir kez süpürüyoruz.
     */
    private fun sweepExpired(now: Long) {
        val previous = lastSweepAt.get()
        if (now - previous < windowMillis) return
        // Yarışı kaybeden iş parçacığı süpürmeyi atlar; biri yapıyorsa yeter.
        if (!lastSweepAt.compareAndSet(previous, now)) return
        windows.entries.removeIf { now - it.value.startedAt >= windowMillis }
    }
}

/**
 * Kimlik uçlarının hız sınırı; iki katmanlı.
 *
 * Yalnızca IP'ye bakmak dağıtık deneme saldırısını durdurmuyor: saldırgan her
 * denemeyi başka bir adresten yaparsa tek bir hesabı sınırsızca deneyebilir.
 * Bu yüzden hesap başına daha sıkı ve daha uzun pencereli ikinci bir sayaç var.
 *
 * Not: iki sayaç da tek sunucunun belleğinde. Çoklu sunucuya geçerken ikisi de
 * Redis gibi paylaşılan bir sayaca taşınmalı.
 */
class AuthThrottle(
    private val perAddress: RequestRateLimiter = RequestRateLimiter(),
    private val perAccount: RequestRateLimiter = RequestRateLimiter(maximumAttempts = 5, windowMillis = 300_000),
    private val trustProxyHeaders: Boolean = false,
) {
    fun checkAddress(call: ApplicationCall, action: String) {
        perAddress.check("$action:${call.clientAddress()}")
    }

    fun checkAccount(action: String, identifier: String) {
        perAccount.check("$action:${identifier.trim().lowercase()}")
    }

    /**
     * `X-Forwarded-For` istemci tarafından uydurulabilir, o yüzden yalnızca
     * güvenilir bir vekil sunucunun arkasında olduğumuz yapılandırıldığında
     * okunuyor. Aksi halde saldırgan her istekte başka bir adres yazıp sınırı
     * tamamen atlardı. Vekil arkasında değilken de `remoteAddress` her istek
     * için yük dengeleyicinin adresini verirdi ve herkes tek kovaya düşerdi —
     * bu yüzden dağıtımda bu bayrağın açılması gerekiyor.
     */
    private fun ApplicationCall.clientAddress(): String {
        if (trustProxyHeaders) {
            val forwarded = request.headers["X-Forwarded-For"]
                ?.substringBefore(',')
                ?.trim()
            if (!forwarded.isNullOrBlank()) return forwarded
        }
        return request.local.remoteAddress
    }
}
