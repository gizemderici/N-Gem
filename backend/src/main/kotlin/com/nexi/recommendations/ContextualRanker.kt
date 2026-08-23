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
)

class ContextualRanker {
    val modelVersion = "nexi-contextual-v1"

    fun rank(
        viewerId: UUID,
        candidates: List<PostDetails>,
        signals: List<RecommendationSignal>,
        context: FeedRecommendationContext,
        now: Instant,
    ): List<RankedPost> {
        if (candidates.isEmpty()) return emptyList()
        val affinities = buildAffinities(signals, context.localHour, now)
        val scored = candidates.map { details ->
            val features = ContentFeatures.from(details)
            val featureScores = features.keys.mapNotNull { key -> affinities[key]?.let { key to it.score } }
            val personalized = featureScores.sumOf { it.second } / sqrt(features.keys.size.coerceAtLeast(1).toDouble())
            val ageHours = Duration.between(details.post.createdAt, now).toMinutes().coerceAtLeast(0) / 60.0
            val freshness = exp(-ageHours / 96.0)
            val quality = ln1p(details.likeCount + details.saveCount * 2.0) / 6.0
            val exploration = deterministicExploration(viewerId, details.post.id, now) * 0.10
            val score = personalized * 2.4 + freshness * 0.9 + quality * 0.35 + exploration
            RankedPost(details, score, explanation(featureScores.maxByOrNull { abs(it.second) }, signals.isEmpty()))
        }
        return diversify(scored)
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

    private fun diversify(scored: List<RankedPost>): List<RankedPost> {
        val remaining = scored.sortedByDescending(RankedPost::score).toMutableList()
        val selected = mutableListOf<RankedPost>()
        while (remaining.isNotEmpty()) {
            val best = remaining.maxBy { candidate ->
                val sameAuthor = selected.count { it.details.post.ownerId == candidate.details.post.ownerId }
                val candidateTokens = ContentFeatures.from(candidate.details).keys.filter { it.startsWith("topic:") || it.startsWith("token:") }.toSet()
                val maxOverlap = selected.maxOfOrNull { previous ->
                    val previousTokens = ContentFeatures.from(previous.details).keys.toSet()
                    if (candidateTokens.isEmpty()) 0.0 else candidateTokens.intersect(previousTokens).size.toDouble() / candidateTokens.size
                } ?: 0.0
                candidate.score - sameAuthor * 0.55 - maxOverlap * 0.25
            }
            selected += best
            remaining -= best
        }
        return selected
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
