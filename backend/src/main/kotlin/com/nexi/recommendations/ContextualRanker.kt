package com.nexi.recommendations

import com.nexi.posts.PostDetails
import com.nexi.topics.TopicCatalog
import java.time.Duration
import java.time.Instant
import java.util.Locale
import java.util.UUID
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln1p
import kotlin.math.sqrt

data class RankedPost(
    val details: PostDetails,
    val score: Double,
    val reason: String,
    /**
     * Yalnızca kişiselleştirme bileşeni; [score] buna güncellik, kalite ve
     * keşif eklendikten sonraki değer. İkisini ayrı taşımak, bir gönderiyi
     * modelin mi yoksa sadece tazeliğinin mi öne çıkardığını ayırt ediyor.
     */
    val rawScore: Double = 0.0,
)

/** Bir sıralama koşusunun tamamı; soy kütüğü kaydı bunun üzerinden yazılır. */
data class RankingResult(
    val ranked: List<RankedPost>,
    /** Kullanıcı profilinin bu koşudaki hâli. */
    val affinities: Map<String, Double>,
    val signalCount: Int,
)

/**
 * Sıralamanın hedefleri ve ağırlıkları.
 *
 * Varsayılanlar bugünkü davranışı **birebir** üretiyor: güvenlik ve adalet
 * ağırlıkları sıfır. Temel sıralama sistemi henüz gerçek veriyle
 * kanıtlanmadan yeni hedefleri herkese açmak, ölçemediğimiz bir değişikliği
 * üretime sokmak olurdu. Açılmaları deney kolu üzerinden yapılmalı.
 */
data class RankingObjectives(
    /** Aynı yazarın her tekrarı için doğrusal ceza. */
    val authorPenalty: Double = 0.55,
    /** Önceki gönderilerle konu/kelime örtüşmesi cezası. */
    val topicOverlapPenalty: Double = 0.25,
    /** Bu değerin altındaki yakınlık "kullanıcı bundan hoşlanmadı" sayılır. */
    val safetyThreshold: Double = -0.5,
    /** Eşiği geçen olumsuzluk için sert ceza; 0 kapalı demek. */
    val safetyPenalty: Double = 0.0,
    /** Bir sayfada tek üreticiye ayrılabilecek en fazla slot; 0 sınırsız. */
    val creatorCap: Int = 0,
    /** Üst sınır aşıldığında uygulanan ceza. */
    val creatorCapPenalty: Double = 5.0,
) {
    init {
        require(authorPenalty >= 0 && topicOverlapPenalty >= 0) { "çeşitlilik cezaları negatif olamaz" }
        require(safetyPenalty >= 0) { "güvenlik cezası negatif olamaz" }
        require(creatorCap >= 0) { "üretici sınırı negatif olamaz" }
    }

    companion object {
        /** Bugünkü üretim davranışı: yalnızca ilgi ve çeşitlilik. */
        val DEFAULT = RankingObjectives()

        /**
         * Güvenlik hedefi açık.
         *
         * Kullanıcının gizlediği içeriğe benzeyen adaylar sert cezalanır.
         * Olumsuz yakınlık kişiselleştirme bileşeninde seyreldiği için
         * (puan özellik sayısının kareköküne bölünüyor) uzun metinli bir
         * gönderi güçlü bir olumsuz sinyali örtebiliyordu.
         */
        val SAFETY_FIRST = RankingObjectives(safetyPenalty = 3.0)

        /**
         * Üretici adaleti açık.
         *
         * Bir sayfada tek üreticiye en fazla iki slot. Yazar cezası doğrusal
         * ve yeterince yüksek puanlı bir üretici tarafından aşılabiliyor;
         * üst sınır aşılamıyor.
         */
        val CREATOR_FAIR = RankingObjectives(creatorCap = 2)
    }
}

class ContextualRanker(
    private val objectives: RankingObjectives = RankingObjectives.DEFAULT,
) {
    val modelVersion = "nexi-contextual-v1"

    /**
     * Özellik çıkarımının sürümü.
     *
     * Modelden ayrı: aynı model farklı özellik sürümüyle başka sonuç verir,
     * bu yüzden eğitim verisinde ikisi de kayıtlı olmalı. Konu özelliği
     * `post_topics`'ten okunmaya başladığında sürüm 2'ye geçti.
     */
    val featureVersion = "nexi-features-v2"

    fun rank(
        viewerId: UUID,
        candidates: List<PostDetails>,
        signals: List<RecommendationSignal>,
        context: FeedRecommendationContext,
        now: Instant,
    ): List<RankedPost> = rankAll(viewerId, candidates, signals, context, now).ranked

    /**
     * @param objectives Bu koşunun hedef ağırlıkları; verilmezse
     *   sıralayıcının kendi yapılandırması kullanılır.
     *
     * Deney kolları aynı sıralayıcıyı farklı hedeflerle çalıştırıyor, o
     * yüzden çağrı başına verilebiliyor. Her kol için ayrı bir sıralayıcı
     * nesnesi tutmak model ve özellik sürümünü de çoğaltır, ve iki kolun
     * sürümü sessizce ayrışabilirdi.
     */
    fun rankAll(
        viewerId: UUID,
        candidates: List<PostDetails>,
        signals: List<RecommendationSignal>,
        context: FeedRecommendationContext,
        now: Instant,
        objectives: RankingObjectives = this.objectives,
    ): RankingResult {
        val affinities = buildAffinities(signals, context.localHour, now)
        val snapshot = affinities.mapValues { (_, value) -> rounded(value.score) }
        if (candidates.isEmpty()) return RankingResult(emptyList(), snapshot, signals.size)

        val scored = candidates.map { details ->
            val features = ContentFeatures.from(details)
            val featureScores = features.keys.mapNotNull { key -> affinities[key]?.let { key to it.score } }
            val personalized = featureScores.sumOf { it.second } / sqrt(features.keys.size.coerceAtLeast(1).toDouble())
            val ageHours = Duration.between(details.post.createdAt, now).toMinutes().coerceAtLeast(0) / 60.0
            val freshness = exp(-ageHours / 96.0)
            val quality = ln1p(details.likeCount + details.saveCount * 2.0) / 6.0
            val exploration = deterministicExploration(viewerId, details.post.id, now) * 0.10
            val score = personalized * 2.4 + freshness * 0.9 + quality * 0.35 + exploration
            RankedPost(
                details = details,
                score = score,
                reason = explanation(featureScores.maxByOrNull { abs(it.second) }, signals.isEmpty()),
                rawScore = personalized,
            )
        }
        return RankingResult(diversify(scored, objectives, affinities), snapshot, signals.size)
    }

    fun profile(signals: List<RecommendationSignal>, currentHour: Int, now: Instant): Pair<List<RecommendationAffinityResponse>, List<RecommendationTimePreferenceResponse>> {
        val affinities = buildAffinities(signals, currentHour, now)
            .filterKeys { it.startsWith("topic:") || it.startsWith("token:") || it.startsWith("media:") }
            .entries
            .filter { it.value.score > 0 }
            .sortedByDescending { it.value.score }
            .take(8)
            .map { (key, value) ->
                RecommendationAffinityResponse(key, featureLabel(key), rounded(value.score))
            }
        val periods = TimePeriod.entries.map { period ->
            val relevant = signals.filter { TimePeriod.fromHour(it.localHour) == period }
            val score = if (relevant.isEmpty()) 0.0 else relevant.sumOf(::reward) / relevant.size
            RecommendationTimePreferenceResponse(period.apiName, rounded(score))
        }
        return affinities to periods
    }

    private fun buildAffinities(
        signals: List<RecommendationSignal>,
        currentHour: Int,
        now: Instant,
    ): Map<String, Affinity> {
        val currentPeriod = TimePeriod.fromHour(currentHour)
        val accumulators = mutableMapOf<String, AffinityAccumulator>()
        signals.forEach { signal ->
            val reward = reward(signal)
            if (reward == 0.0) return@forEach
            val ageDays = Duration.between(signal.occurredAt, now).toHours().coerceAtLeast(0) / 24.0
            val recencyWeight = exp(-ageDays / 30.0)
            val contextWeight = if (TimePeriod.fromHour(signal.localHour) == currentPeriod) 1.0 else 0.30
            val evidenceWeight = recencyWeight * contextWeight
            SignalFeatures.from(signal).keys.forEach { key ->
                val accumulator = accumulators.getOrPut(key, ::AffinityAccumulator)
                accumulator.weightedReward += reward * evidenceWeight
                accumulator.evidence += abs(reward) * evidenceWeight
            }
        }
        return accumulators.mapValues { (_, value) ->
            Affinity(value.weightedReward / (2.0 + value.evidence), value.evidence)
        }
    }

    /**
     * Çok hedefli yeniden sıralama: ilgi, çeşitlilik, güvenlik ve üretici
     * adaleti.
     *
     * Açgözlü seçim; her adımda kalan adaylar arasından hedeflerin toplamı en
     * yüksek olanı alıyor. Tek geçişte sıralamak yetmez çünkü çeşitlilik ve
     * adalet cezaları **o ana kadar seçilenlere** bağlı.
     */
    private fun diversify(
        scored: List<RankedPost>,
        objectives: RankingObjectives,
        affinities: Map<String, Affinity>,
    ): List<RankedPost> {
        val remaining = scored.sortedByDescending(RankedPost::score).toMutableList()
        val selected = mutableListOf<RankedPost>()
        val perCreator = mutableMapOf<UUID, Int>()

        while (remaining.isNotEmpty()) {
            val best = remaining.maxBy { candidate ->
                val ownerId = candidate.details.post.ownerId
                val sameAuthor = perCreator[ownerId] ?: 0
                val candidateTokens = ContentFeatures.from(candidate.details).keys
                    .filter { it.startsWith("topic:") || it.startsWith("token:") }
                    .toSet()
                val maxOverlap = selected.maxOfOrNull { previous ->
                    val previousTokens = ContentFeatures.from(previous.details).keys.toSet()
                    if (candidateTokens.isEmpty()) {
                        0.0
                    } else {
                        candidateTokens.intersect(previousTokens).size.toDouble() / candidateTokens.size
                    }
                } ?: 0.0

                candidate.score -
                    sameAuthor * objectives.authorPenalty -
                    maxOverlap * objectives.topicOverlapPenalty -
                    safetyPenalty(candidate, affinities, objectives) -
                    creatorCapPenalty(sameAuthor, objectives)
            }
            selected += best
            perCreator[best.details.post.ownerId] = (perCreator[best.details.post.ownerId] ?: 0) + 1
            remaining -= best
        }
        return selected
    }

    /**
     * Güvenlik: kullanıcının daha önce gizlediği ya da şikâyet ettiği içeriğe
     * benzeyen adayları bastırır.
     *
     * Olumsuz yakınlık zaten kişiselleştirme bileşenine giriyor, ama orada
     * doğrusal: yeterince taze ve popüler bir gönderi güçlü bir olumsuz
     * sinyali dengeleyip yine üste çıkabiliyor. Eşiği geçen olumsuzluk bu
     * yüzden ayrıca ve sert cezalandırılıyor.
     */
    private fun safetyPenalty(
        candidate: RankedPost,
        affinities: Map<String, Affinity>,
        objectives: RankingObjectives,
    ): Double {
        if (objectives.safetyPenalty <= 0.0) return 0.0
        val worst = ContentFeatures.from(candidate.details).keys
            .filter { it.startsWith("topic:") || it.startsWith("creator:") }
            .minOfOrNull { key -> affinities[key]?.score ?: 0.0 }
            ?: 0.0
        return if (worst < objectives.safetyThreshold) objectives.safetyPenalty else 0.0
    }

    /**
     * Üretici adaleti: bir sayfada aynı üreticiye ayrılan slot sayısını
     * sınırlar.
     *
     * Yazar cezası doğrusal ve yeterince yüksek puanlı bir üretici tarafından
     * aşılabiliyor; üst sınır ise aşılamıyor. İkisi farklı işler yapıyor,
     * biri diğerinin yerini tutmuyor.
     */
    private fun creatorCapPenalty(alreadySelected: Int, objectives: RankingObjectives): Double =
        if (objectives.creatorCap > 0 && alreadySelected >= objectives.creatorCap) {
            objectives.creatorCapPenalty
        } else {
            0.0
        }

    private fun explanation(best: Pair<String, Double>?, coldStart: Boolean): String {
        if (coldStart || best == null || best.second <= 0) return "Yeni ve toplulukta ilgi gören içerik"
        return when {
            best.first.startsWith("topic:") -> "Bu saatte ${featureLabel(best.first)} ilgine uygun"
            best.first.startsWith("creator:") -> "Bu üreticiyle önceki etkileşimlerin nedeniyle"
            best.first == "media:video" -> "Bu saatte video izleme tercihin nedeniyle"
            best.first == "media:image" -> "Görsel içerik tercihin nedeniyle"
            else -> "Yakın zamanda ilgilendiğin konular nedeniyle"
        }
    }

    private fun deterministicExploration(viewerId: UUID, postId: UUID, now: Instant): Double {
        val day = now.epochSecond / 86_400
        val mixed = viewerId.mostSignificantBits xor postId.leastSignificantBits xor day
        return (abs(mixed % 10_000).toDouble() / 10_000.0)
    }

    private data class AffinityAccumulator(var weightedReward: Double = 0.0, var evidence: Double = 0.0)
    private data class Affinity(val score: Double, val evidence: Double)
}

/**
 * Bir gönderiden çıkarılan özellik anahtarları.
 *
 * Eşitlik testi bunları referans dosyasına yazıyor: Türkçe kelime ayrıştırması
 * Kotlin'de kalıyor, Python tarafı aynı özelliklerden aynı aritmetiği
 * yürütmekle yükümlü.
 */
internal fun contentFeatureKeys(details: PostDetails): Set<String> = ContentFeatures.from(details).keys

/** Bir sinyalden çıkarılan özellik anahtarları; bkz. [contentFeatureKeys]. */
internal fun signalFeatureKeys(signal: RecommendationSignal): Set<String> = SignalFeatures.from(signal).keys

private data class ContentFeatures(val keys: Set<String>) {
    companion object {
        fun from(details: PostDetails): ContentFeatures {
            val mediaType = when {
                details.media.any { it.mimeType.startsWith("video/") } -> "video"
                details.media.isNotEmpty() -> "image"
                else -> "text"
            }
            return ContentFeatures(
                buildSet {
                    add("creator:${details.post.ownerId}")
                    add("media:$mediaType")
                    addAll(topicFeatures(details.topics.map { it.slug }, details.post.body))
                    addAll(textTokens(details.post.body))
                }
            )
        }
    }
}

private data class SignalFeatures(val keys: Set<String>) {
    companion object {
        fun from(signal: RecommendationSignal): SignalFeatures {
            signal.targetFeature?.let { return SignalFeatures(setOf(normalizeTargetFeature(it))) }
            return SignalFeatures(
                buildSet {
                    signal.authorId?.let { add("creator:$it") }
                    signal.mediaType?.let { add("media:$it") }
                    addAll(topicFeatures(signal.topicSlugs, signal.body))
                    signal.body?.let { addAll(textTokens(it)) }
                }
            )
        }
    }
}

private enum class TimePeriod(val apiName: String) {
    MORNING("morning"),
    WORK("work_hours"),
    EVENING("evening"),
    NIGHT("night");

    companion object {
        fun fromHour(hour: Int) = when (hour.coerceIn(0, 23)) {
            in 6..8 -> MORNING
            in 9..17 -> WORK
            in 18..21 -> EVENING
            else -> NIGHT
        }
    }
}

private val turkishLocale = Locale.forLanguageTag("tr-TR")
private val tokenRegex = Regex("[#\\p{L}\\p{N}]{3,}")
private val stopWords = setOf(
    "ama", "bir", "bize", "bizi", "bu", "çok", "daha", "değil", "diye", "gibi", "için", "ile",
    "olan", "olarak", "sonra", "şey", "var", "veya", "yeni", "the", "and", "for", "that", "this", "with"
)
/**
 * Konusuz gönderiler için metinden konu tahmini.
 *
 * Anahtarlar [TopicCatalog] slug'larıdır; daha önce buradaki liste mobil
 * uygulamalardaki İngilizce sabitleri kopyalıyordu ve hiçbir katalog konusuyla
 * eşleşmiyordu. Eşleşme önek üzerinden yapılır: Türkçe eklerle uzayan bir dil
 * olduğu için "spor" hem "sporcu" hem "sportif" ile eşleşmeli.
 */
private val topicAliases: Map<String, Set<String>> = mapOf(
    "teknoloji" to setOf("teknoloji", "yazılım", "kodlama", "mobil", "uygulama", "donanım"),
    "yapay-zeka" to setOf("yapay", "zeka", "zekâ", "algoritma", "model"),
    "sanat" to setOf("sanat", "tasarım", "arayüz", "illüstrasyon", "sergi", "tiyatro", "sinema"),
    "egitim" to setOf("eğitim", "öğren", "ders", "kitap", "kurs", "sınav"),
    "spor" to setOf("spor", "futbol", "basketbol", "koşu", "maç", "antrenman"),
    "gundem" to setOf("gündem", "haber", "sondakika", "açıklama"),
    "bilim" to setOf("bilim", "araştırma", "uzay", "fizik", "biyoloji", "deney"),
    "oyun" to setOf("oyun", "gaming", "espor", "konsol"),
    "muzik" to setOf("müzik", "şarkı", "albüm", "konser", "sahne"),
    "saglik" to setOf("sağlık", "beslenme", "uyku", "diyet", "egzersiz"),
    "girisimcilik" to setOf("girişim", "startup", "yatırım", "kariyer"),
    "seyahat" to setOf("seyahat", "gezgin", "rota", "tatil"),
)

/**
 * Yazarın seçtiği konular birincil kaynaktır; gönderi etiketlenmişse metinden
 * tahmin yürütülmez. Kabul kriteri bunu gerektiriyor: konulu bir gönderi,
 * metninde o konunun kelimeleri geçmese de doğru özelliği taşımalı. Etiket
 * varken üstüne tahmin eklemek de yazarın beyanını sulandırırdı.
 */
private fun topicFeatures(slugs: List<String>, text: String?): Set<String> {
    val declared = slugs.map { TopicCatalog.normalize(it) }.filter(TopicCatalog::isKnown)
    if (declared.isNotEmpty()) return declared.mapTo(mutableSetOf()) { "topic:$it" }
    if (text == null) return emptySet()
    val tokens = tokenize(text)
    return buildSet {
        topicAliases.forEach { (slug, aliases) ->
            if (tokens.any { token -> aliases.any(token::startsWith) }) add("topic:$slug")
        }
    }
}

private fun textTokens(text: String): Set<String> = tokenize(text).mapTo(mutableSetOf()) { "token:$it" }

private fun tokenize(text: String): Set<String> = tokenRegex.findAll(text.lowercase(turkishLocale))
    .map { it.value.trimStart('#') }
    .filterNot { it in stopWords }
    .take(16)
    .toSet()

/**
 * `lowercase` burada ROOT ile çağrılmalı: Türkçe yerel ayarında "TEKNOLOJI"
 * noktasız `ı` ile "teknolojı" olur ve hiçbir slug'a denk gelmez.
 */
private fun normalizeTargetFeature(value: String): String {
    val withoutPrefix = TopicCatalog.normalize(value).removePrefix("topic:")
    val normalized = withoutPrefix.replace(Regex("[^a-z0-9_-]"), "").take(64)
    return "topic:$normalized"
}

private fun reward(signal: RecommendationSignal): Double = when (signal.eventType) {
    // Sunum ve gösterim tercih kanıtı değil: ikisi de kullanıcının bir şey
    // seçtiğini göstermez, yalnızca içeriğin önüne geldiğini söyler.
    RecommendationEventType.SESSION_STARTED,
    RecommendationEventType.FEED_SERVED,
    RecommendationEventType.CONTENT_IMPRESSION -> 0.0
    RecommendationEventType.INTEREST_SELECTED -> 2.8
    RecommendationEventType.CONTENT_VIEW -> {
        val dwell = ((signal.dwellMillis ?: 0L).coerceAtMost(60_000) / 60_000.0) * 1.1
        val completion = (signal.completionRatio ?: 0.0).coerceIn(0.0, 1.0) * 1.2
        0.15 + dwell + completion
    }
    RecommendationEventType.CONTENT_COMPLETE -> 2.0
    RecommendationEventType.CONTENT_LIKED -> 2.2
    RecommendationEventType.CONTENT_SAVED -> 3.0
    RecommendationEventType.CONTENT_SHARED -> 3.2
    RecommendationEventType.RECOMMENDATION_REASON_OPENED -> 0.35
    RecommendationEventType.CONTENT_HIDDEN -> -4.0
    RecommendationEventType.CONTENT_REPORTED -> -6.0
}

private fun featureLabel(key: String): String {
    val raw = key.substringAfter(':')
    TopicCatalog.label(raw)?.let { return it }
    return when (raw) {
        "video" -> "Video"
        "image" -> "Görsel"
        "text" -> "Metin"
        else -> raw.replaceFirstChar { it.uppercase(turkishLocale) }
    }
}

private fun rounded(value: Double): Double = kotlin.math.round(value * 1_000.0) / 1_000.0
