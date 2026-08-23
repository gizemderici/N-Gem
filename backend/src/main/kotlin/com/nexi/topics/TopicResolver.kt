package com.nexi.topics

/**
 * İlgi olaylarındaki `targetFeature` değerini kanonik konu slug'ına çevirir.
 *
 * İstemciler ellerinde ne varsa onu gönderebilsin diye hem slug hem konu
 * kimliği kabul edilir; depoya her zaman slug yazılır. Tanınmayan değer
 * kabul edilmez: sessizce kaydetmek, mobil taraftaki uyuşmayan katalog
 * hatasının uzun süre fark edilmemesinin sebebiydi.
 *
 * Çalışma zamanında yetki veritabanındadır. Katalog migration ile geldiği ve
 * uygulama ömrü boyunca değişmediği için tek seferlik önbellek yeterli; yeni
 * bir konu eklendiğinde zaten yeni sürüm yayınlanıyor. Önbelleği atlayan bir
 * yol bırakmıyoruz, aksi hâlde her geçersiz değer bir veritabanı sorgusu
 * tetiklerdi.
 */
class TopicResolver(private val repository: TopicRepository) {
    @Volatile
    private var index: Map<String, String>? = null

    /** Slug ya da konu kimliği verildiğinde kanonik slug; tanınmıyorsa `null`. */
    fun canonicalSlug(raw: String): String? {
        val normalized = TopicCatalog.normalize(raw).removePrefix("topic:")
        if (normalized.isEmpty()) return null
        return index().get(normalized)
    }

    /** Aktif katalogdaki bütün slug'lar. */
    fun activeSlugs(): Set<String> = index().values.toSortedSet()

    /** Testlerin ve katalog değişikliğinin önbelleği tazelemesi için. */
    fun refresh() {
        index = load()
    }

    private fun index(): Map<String, String> = index ?: load().also { index = it }

    /**
     * Aynı slug iki anahtarla girilir: slug'ın kendisi ve konunun kimliği.
     * Böylece arama tek bir sözlük okumasıyla biter.
     */
    private fun load(): Map<String, String> = buildMap {
        repository.listActive().forEach { topic ->
            val slug = TopicCatalog.normalize(topic.slug)
            put(slug, slug)
            put(TopicCatalog.normalize(topic.id.toString()), slug)
        }
    }
}
