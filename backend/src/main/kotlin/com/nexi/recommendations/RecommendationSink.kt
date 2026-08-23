package com.nexi.recommendations

import java.time.Instant
import java.util.UUID

/**
 * Backend'in kendi işlemi içinde öneri sinyali üretmesi için dar bir arayüz.
 *
 * Beğeni, kaydetme ve şikâyet zaten sunucuda yazılan işlemler. Sinyali orada
 * üretmek, istemcinin ağı kesildiğinde ya da uygulama kapandığında olayın
 * kaybolmamasını sağlıyor — Faz 1'in "kayıpsız toplama" hedefinin sunucu
 * tarafındaki yarısı bu.
 *
 * Arayüz `RecommendationRepository`'nin tamamını taşımıyor: şikâyet ve gönderi
 * servislerinin okuma yetkisine ihtiyacı yok, yalnızca yazmaya var.
 */
fun interface RecommendationSink {
    fun emit(events: List<RecommendationEvent>)

    companion object {
        /** Öneri deposu olmayan bağlamlar (testler, yerel kurulum) için. */
        val NOOP = RecommendationSink { }
    }
}

/**
 * Sunucunun ürettiği tek bir etkileşim olayı.
 *
 * `clientEventId` rastgele üretilir: çağıran zaten yalnızca gerçek geçişlerde
 * (beğenilmemişken beğenildiğinde) olay yazıyor, dolayısıyla tekrar yazımı
 * engelleyecek sabit bir kimliğe gerek yok. Sabit kimlik seçilseydi kullanıcı
 * beğeniyi geri alıp yeniden beğendiğinde ikinci etkileşim hiç kaydedilmezdi.
 */
internal fun serverEvent(
    userId: UUID,
    postId: UUID,
    type: RecommendationEventType,
    surface: String,
    now: Instant,
    sessionId: UUID = UUID.randomUUID(),
    position: Int? = null,
    feedRequestId: UUID? = null,
): RecommendationEvent = RecommendationEvent(
    id = UUID.randomUUID(),
    userId = userId,
    postId = postId,
    clientEventId = UUID.randomUUID(),
    sessionId = sessionId,
    feedRequestId = feedRequestId,
    eventType = type,
    surface = surface,
    position = position,
    dwellMillis = null,
    completionRatio = null,
    // Sunucu kullanıcının yerel saatini bilmiyor; UTC saati yazıp saat dilimini
    // sıfır bırakmak, uydurma bir yerel saat yazmaktan dürüst.
    localHour = now.atZone(java.time.ZoneOffset.UTC).hour,
    timezoneOffsetMinutes = 0,
    targetFeature = null,
    occurredAt = now,
    receivedAt = now,
    schemaVersion = EventContract.CURRENT_VERSION,
    appVersion = null,
    platform = EventPlatform.BACKEND,
)
