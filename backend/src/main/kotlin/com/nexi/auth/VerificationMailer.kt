package com.nexi.auth

import org.slf4j.LoggerFactory

/**
 * Doğrulama kodunu kullanıcıya ulaştıran taraf.
 *
 * Gerçek bir sağlayıcı (SendGrid, Resend, SES, kendi SMTP'niz…) takmak için tek
 * yapılacak şey bu arayüzü uygulayan bir sınıf yazıp `Application.module()`
 * içinde [LoggingVerificationMailer] yerine onu geçmek. `AuthService` hangi
 * sağlayıcının kullanıldığını bilmiyor.
 */
fun interface VerificationMailer {
    fun send(email: String, code: String, purpose: VerificationPurpose)
}

/**
 * Sağlayıcı takılana kadarki geçici uygulama.
 *
 * Geliştirmede kodu log'a yazar. Üretimde **yazmaz** — kodu log'a düşürmek onu
 * log'a erişen herkese vermek olurdu — bunun yerine gürültülü bir hata basar,
 * çünkü bu durumda kayıt akışı fiilen çalışmıyor demektir.
 */
class LoggingVerificationMailer(private val exposeCodes: Boolean) : VerificationMailer {
    private val logger = LoggerFactory.getLogger(LoggingVerificationMailer::class.java)

    override fun send(email: String, code: String, purpose: VerificationPurpose) {
        if (exposeCodes) {
            logger.info("Development verification code: email={}, purpose={}, code={}", email, purpose, code)
        } else {
            logger.error(
                "Verification e-mail was NOT sent: no provider is configured. " +
                    "Registration and password reset cannot complete. purpose={}",
                purpose,
            )
        }
    }
}
