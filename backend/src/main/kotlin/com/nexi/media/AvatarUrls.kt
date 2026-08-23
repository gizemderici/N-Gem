package com.nexi.media

import java.time.Duration

/** Süreli avatar adresi ve ne kadar geçerli olduğu. */
data class AvatarUrl(val url: String, val expiresInSeconds: Long)

/**
 * Avatar adresleri de gönderi görselleri gibi süreli imzalı bağlantılar.
 *
 * Profil, gönderi yazarı, yorum yazarı ve takipçi listesi cevaplarının hepsi
 * aynı süreyi kullansın diye üretim tek yerde. Depolama anahtarı sunucu içinde
 * kalır; dışarı yalnızca imzalı adres çıkar.
 */
object AvatarUrls {
    val EXPIRY: Duration = Duration.ofMinutes(15)

    fun of(storage: ObjectStorage, storageKey: String?): AvatarUrl? = storageKey?.let {
        AvatarUrl(storage.createDownloadUrl(it, EXPIRY), EXPIRY.seconds)
    }
}
