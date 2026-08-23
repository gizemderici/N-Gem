package com.nexi.media

import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Duration

/**
 * Yarım kalan yüklemeleri toplar.
 *
 * Kullanıcı yükleme adresi alıp dosyayı hiç göndermezse ya da gönderip
 * tamamlama adımını çağırmazsa kayıt `PENDING` durumunda takılı kalıyor;
 * dosya da depoda duruyor olabiliyor. Bunlar hiçbir gönderiye bağlı olmadığı
 * için kimse fark etmeden birikiyorlar.
 *
 * Süpürme idempotent: aynı kaydı iki kez işlemek zarar vermez.
 */
class MediaJanitor(
    private val repository: MediaRepository,
    private val storage: ObjectStorage,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val logger = LoggerFactory.getLogger(MediaJanitor::class.java)

    /**
     * @param olderThan Bundan daha uzun süredir dokunulmamış `PENDING` kayıtlar temizlenir.
     * @return Temizlenen kayıt sayısı.
     */
    fun sweepAbandonedUploads(olderThan: Duration, batchSize: Int = 200): Int {
        val cutoff = clock.instant().minus(olderThan)
        val stale = repository.findStale(MediaStatus.PENDING, cutoff, batchSize)
        if (stale.isEmpty()) return 0

        var cleaned = 0
        for (asset in stale) {
            // Dosya hiç yüklenmemiş olabilir; yokluğu hata değil.
            runCatching { storage.delete(asset.storageKey) }.onFailure {
                logger.warn("Could not remove abandoned object: key={}", asset.storageKey, it)
            }
            if (repository.markStatus(asset.id, MediaStatus.DELETED, clock.instant())) cleaned++
        }
        logger.info("Swept abandoned uploads: found={}, cleaned={}", stale.size, cleaned)
        return cleaned
    }
}
