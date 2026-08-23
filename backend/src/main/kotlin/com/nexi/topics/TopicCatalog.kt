package com.nexi.topics

import java.util.Locale

/**
 * Konu kimliklerinin tek kaynağı.
 *
 * Daha önce üç ayrı liste vardı: mobil uygulamalardaki `technology`/`design`
 * gibi İngilizce sabitler, veritabanındaki `teknoloji`/`sanat` slug'ları ve
 * sıralayıcının içindeki mobil listenin kopyası. Üçünün kesişimi boş olduğu
 * için seçilen ilgi alanları hiçbir zaman `user_topics` tablosuna yazılmıyor,
 * konu yakınlıkları da yalnızca gönderi metninden tahmin ediliyordu.
 *
 * Buradaki liste `V5__create_topics.sql` ile birebir aynıdır. İkisinin
 * ayrışmadığını `TopicCatalogIntegrationTest` gerçek veritabanına karşı
 * doğrular; katalog değişecekse migration ile bu dosya birlikte değişmeli.
 */
object TopicCatalog {
    /** Slug -> görünen ad. Sıra `display_order` ile aynı. */
    val LABELS: Map<String, String> = linkedMapOf(
        "teknoloji" to "Teknoloji",
        "yapay-zeka" to "Yapay Zekâ",
        "sanat" to "Sanat ve Tasarım",
        "egitim" to "Eğitim",
        "spor" to "Spor",
        "gundem" to "Gündem",
        "bilim" to "Bilim",
        "oyun" to "Oyun",
        "muzik" to "Müzik",
        "saglik" to "Sağlık ve Yaşam",
        "girisimcilik" to "Girişimcilik",
        "seyahat" to "Seyahat",
    )

    val SLUGS: Set<String> = LABELS.keys

    fun isKnown(slug: String): Boolean = slug in LABELS

    /** Katalogda olmayan bir slug için `null`; çağıran kendi yedeğini seçer. */
    fun label(slug: String): String? = LABELS[slug]

    /**
     * Karşılaştırma her zaman ROOT yerel ayarıyla yapılmalı.
     *
     * Türkçe yerel ayarında `"TEKNOLOJI".lowercase()` noktasız `ı` üretip
     * `teknolojı` verir; katalog slug'ları ASCII olduğu için bu değer hiçbir
     * konuyla eşleşmez ve büyük harfle gelen bir ilgi seçimi sessizce
     * tanınmaz hâle gelirdi.
     */
    fun normalize(raw: String): String = raw.trim().lowercase(Locale.ROOT)
}
