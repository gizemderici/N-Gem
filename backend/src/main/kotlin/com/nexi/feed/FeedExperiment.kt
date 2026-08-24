package com.nexi.feed

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID

/**
 * Akış deneyinin kolları.
 *
 * Kol adı soy kütüğüne yazılır; hangi sıralamanın hangi koldan geldiği
 * sonradan ancak böyle söylenebilir.
 */
enum class FeedVariant(val wireName: String) {
    /** Kişiselleştirme yok; kronolojik akış. Karşılaştırmanın taban çizgisi. */
    CONTROL("control"),

    /** Bugünkü `nexi-contextual-v1`. */
    HEURISTIC("heuristic"),

    /**
     * Eğitilmiş model.
     *
     * Bugün yalnızca gölge modda anlamlı: `recommender-lab` henüz üretime
     * aday bir model üretmedi, backend'de yüklü bir ağırlık dosyası yok.
     * Kol şimdiden tanımlı ki Faz 5 bir model ürettiğinde yalnızca ayar
     * değişsin, kod değil.
     */
    LEARNED("learned");

    companion object {
        fun fromWire(raw: String): FeedVariant? =
            entries.firstOrNull { it.wireName.equals(raw.trim(), ignoreCase = true) }
    }
}

/**
 * Deney yapılandırması.
 *
 * Öldürme anahtarı (`enabled = false`) tek ayarla her şeyi kronolojiğe
 * döndürür: bir model hatası fark edildiğinde yeni sürüm beklemek zorunda
 * kalmamak gerekir.
 */
data class FeedExperimentConfig(
    val enabled: Boolean = true,
    /**
     * Kullanıcının atanacağı kollar ve ağırlıkları. Boşsa herkes
     * [FeedVariant.HEURISTIC] alır.
     */
    val weights: Map<FeedVariant, Int> = mapOf(FeedVariant.HEURISTIC to 1),
    /**
     * Gölgede hesaplanacak kol; kullanıcıya gösterilmez, yalnızca kaydedilir.
     * `null` ise gölge koşusu yapılmaz.
     */
    val shadow: FeedVariant? = null,
    /** Grupları kaydırmadan deneyi yeniden başlatmak için. */
    val salt: String = "nexi-feed-v1",
) {
    init {
        require(weights.values.all { it >= 0 }) { "deney ağırlıkları negatif olamaz" }
    }

    val totalWeight: Int get() = weights.values.sum()

    companion object {
        /**
         * `FEED_EXPERIMENT=heuristic:9,learned:1` biçiminde okunur.
         *
         * Ayrıştırılamayan bir değer sessizce yok sayılmaz; yanlış yazılmış
         * bir ayarın kullanıcıların yarısını başka bir kola atması, fark
         * edilmesi en zor hatalardan biri olurdu.
         */
        fun parse(
            raw: String?,
            enabled: Boolean,
            shadow: String?,
            salt: String,
        ): FeedExperimentConfig {
            val weights = raw?.split(',')
                ?.mapNotNull { it.trim().takeIf(String::isNotEmpty) }
                ?.associate { entry ->
                    val parts = entry.split(':')
                    require(parts.size == 2) { "geçersiz deney tanımı: '$entry'" }
                    val variant = FeedVariant.fromWire(parts[0])
                        ?: throw IllegalArgumentException("bilinmeyen deney kolu: '${parts[0]}'")
                    val weight = parts[1].trim().toIntOrNull()
                        ?: throw IllegalArgumentException("geçersiz ağırlık: '${parts[1]}'")
                    require(weight >= 0) { "deney ağırlığı negatif olamaz: '$entry'" }
                    variant to weight
                }
                ?.takeIf { it.isNotEmpty() && it.values.sum() > 0 }
                ?: mapOf(FeedVariant.HEURISTIC to 1)

            val shadowVariant = shadow?.trim()?.takeIf(String::isNotEmpty)?.let {
                FeedVariant.fromWire(it) ?: throw IllegalArgumentException("bilinmeyen gölge kolu: '$it'")
            }

            return FeedExperimentConfig(
                enabled = enabled,
                weights = weights,
                shadow = shadowVariant,
                salt = salt,
            )
        }
    }
}

/**
 * Kullanıcıyı bir deney koluna atar.
 *
 * Atama kullanıcı kimliğinin özetinden geliyor, kayıt tutulmuyor: kullanıcı
 * her istekte aynı kola düşer, oturum değiştirmek ya da uygulamayı yeniden
 * kurmak grubu kaydırmaz. Rastgele atayıp saklamak, hem fazladan bir tablo
 * hem de "atama yazılamadı" diye yeni bir hata yolu demekti.
 */
class FeedExperiments(private val config: FeedExperimentConfig) {

    fun variantFor(userId: UUID): FeedVariant {
        if (!config.enabled) return FeedVariant.CONTROL
        val total = config.totalWeight
        if (total <= 0) return FeedVariant.HEURISTIC

        val bucket = (bucketOf(userId) % total).toInt()
        var cursor = 0
        // Sıralama sabit olmalı; sözlük sırası değişirse aynı kullanıcı başka
        // kola düşerdi.
        for (variant in FeedVariant.entries) {
            cursor += config.weights[variant] ?: 0
            if (bucket < cursor) return variant
        }
        return FeedVariant.HEURISTIC
    }

    /** Gölgede hesaplanacak kol; gösterilen kolla aynıysa gölge koşusu gereksiz. */
    fun shadowFor(userId: UUID): FeedVariant? {
        if (!config.enabled) return null
        val shadow = config.shadow ?: return null
        return shadow.takeIf { it != variantFor(userId) }
    }

    private fun bucketOf(userId: UUID): Long {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("${config.salt}:$userId".toByteArray(StandardCharsets.UTF_8))
        var value = 0L
        // İlk yedi bayt: sekizinci bayt işaret bitini kirletirdi.
        for (index in 0 until 7) {
            value = (value shl 8) or (digest[index].toLong() and 0xFF)
        }
        return value
    }
}
