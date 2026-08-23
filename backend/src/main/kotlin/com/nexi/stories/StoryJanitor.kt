package com.nexi.stories

import com.nexi.media.ObjectStorage
import org.slf4j.LoggerFactory
import java.time.Clock

/**
 * Süresi dolmuş hikâyeleri kapatır ve medyalarını serbest bırakır.
 *
 * Süre dolduğunda hikâye akıştan zaten düşüyor (sorgular `expires_at`'e bakıyor),
 * ama kayıt ve dosya duruyor. Bu iş olmadan her hikâye kalıcı olarak depoda
 * birikirdi — `MediaJanitor`'ın yarım kalan yüklemeler için yaptığının aynısı.
 */
class StoryJanitor(
    private val repository: StoryRepository,
    private val storage: ObjectStorage,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val logger = LoggerFactory.getLogger(StoryJanitor::class.java)

    fun sweepExpired(batchSize: Int = 200): Int {
        val expired = repository.expireOlderThan(clock.instant(), batchSize)
        if (expired.isEmpty()) return 0

        expired.forEach { (_, storageKey) ->
            runCatching { storage.delete(storageKey) }.onFailure {
                logger.warn("Could not remove expired story media: key={}", storageKey, it)
            }
        }
        logger.info("Swept expired stories: count={}", expired.size)
        return expired.size
    }
}
