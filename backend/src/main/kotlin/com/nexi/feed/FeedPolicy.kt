package com.nexi.feed

import com.nexi.auth.ApiException
import com.nexi.media.ObjectStorage
import com.nexi.posts.CandidateSource
import com.nexi.posts.FeedCursor
import com.nexi.posts.FeedResponse
import com.nexi.posts.PostDetails
import com.nexi.posts.PostRepository
import com.nexi.posts.PostResponse
import com.nexi.posts.toResponse
import com.nexi.recommendations.AlwaysGrantedConsentRepository
import com.nexi.recommendations.ConsentRepository
import com.nexi.recommendations.ContextualRanker
import com.nexi.recommendations.EventPlatform
import com.nexi.recommendations.FeedRecommendationContext
import com.nexi.recommendations.RecommendationEvent
import com.nexi.recommendations.RecommendationEventType
import com.nexi.recommendations.RankingObjectives
import com.nexi.recommendations.RecommendationRepository
import com.nexi.topics.TopicService
import io.ktor.http.HttpStatusCode
import org.slf4j.LoggerFactory
import java.nio.charset.StandardCharsets
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.Base64
import java.util.UUID

/**
 * Akışın tek politikası.
 *
 * Önceden iki ayrı uç vardı: `/api/v1/feed` kullanıcının seçtiği konuları
 * karıştırıyor, `/api/v1/posts/feed` ise davranıştan öğreniyordu. Mobil
 * uygulamalar yalnızca ikincisini çağırdığı için seçilen ilgi alanları hiç
 * okunmuyordu. Artık ikisi de buraya bağlı; konu tercihi bir aday kaynağı
 * olarak sıralamanın içinde.
 *
 * Sayfalama, aday kümesini ve sinyalleri ilk sayfada dondurup imleçte taşınan
 * `rankedAt` anına göre yeniden sıralayarak çalışır. Puan imleçte taşınmıyor:
 * dondurulmuş girdiyle sıralama tekrar üretilebilir olduğu için konum
 * yetiyor, ve konum tabanlı sayfalama tekrar üretmesi imkânsız olduğu için
 * "aynı gönderi iki sayfada" hatasını yapısal olarak engelliyor.
 */
class FeedPolicy(
    private val posts: PostRepository,
    private val recommendations: RecommendationRepository,
    private val ranker: ContextualRanker,
    private val storage: ObjectStorage,
    private val clock: Clock = Clock.systemUTC(),
    private val lineage: FeedLineageRepository = NoopFeedLineageRepository,
    private val consents: ConsentRepository = AlwaysGrantedConsentRepository,
    private val experiments: FeedExperiments = FeedExperiments(FeedExperimentConfig()),
    private val fallbacks: FeedFallbackRecorder = FeedFallbackRecorder.NOOP,
) {
    private val logger = LoggerFactory.getLogger(FeedPolicy::class.java)
    private val mediaUrlExpiry: Duration = Duration.ofMinutes(15)

    fun feed(
        viewerId: UUID,
        rawCursor: String?,
        requestedLimit: Int?,
        context: FeedRecommendationContext? = null,
        personalizationEnabled: Boolean = true,
    ): FeedResponse {
        val limit = (requestedLimit ?: DEFAULT_LIMIT).coerceIn(1, MAX_LIMIT)
        val cursor = rawCursor?.let { decodeCursor(it) }

        // Kişiselleştirme kapalıysa profil hiç okunmaz ve sunum olayı yazılmaz.
        // Rıza vermemiş kullanıcı için de aynı yol: istek `personalized=true`
        // dese bile profil okunmaz. Deney kolu CONTROL ise ve öldürme anahtarı
        // kapalıysa da buraya düşülür.
        val variant = experiments.variantFor(viewerId)
        val skipReason = when {
            !personalizationEnabled || !recommendations.personalizationAvailable ||
                variant == FeedVariant.CONTROL -> FallbackReason.EXPERIMENT_DISABLED
            !consents.find(viewerId).granted -> FallbackReason.CONSENT_MISSING
            else -> null
        }
        if (skipReason != null) {
            // İstemcinin `personalized=false` demesi bir arıza değil; yalnızca
            // beklenmedik sebepler kaydediliyor ki alarm oranı gürültüye
            // boğulmasın.
            if (personalizationEnabled) recordFallback(viewerId, skipReason, variant, null)
            // Soy kütüğü yalnızca *çalışan bir deneyin* kontrol kolu için
            // yazılıyor. Öldürme anahtarı da CONTROL döndürüyor ama o bir kol
            // değil, öneri verisi yazmayı durdurma kararı; rızası olmayan
            // kullanıcı da buraya düşüyor ve `feed_requests` kişisel veri
            // taşıyor. İkisinde de kayıt yanlış olurdu.
            val controlArm = skipReason == FallbackReason.EXPERIMENT_DISABLED &&
                experiments.running &&
                personalizationEnabled &&
                variant == FeedVariant.CONTROL &&
                recommendations.personalizationAvailable &&
                consents.find(viewerId).granted
            return chronological(viewerId, cursor as? Chronological, limit, context, controlArm)
        }
        if (cursor is Chronological) {
            // Kullanıcı akış ortasında kişiselleştirmeyi açtıysa kronolojik
            // imleçle devam etmek doğru; yeni sıralamaya atlamak okuduğu yeri
            // kaybettirirdi.
            return chronological(viewerId, cursor, limit, context, recordControl = false)
        }

        // Sıralama hatası akışı düşürmeyi hak etmez: kullanıcı bozuk bir model
        // yüzünden boş ekran görmemeli, kronolojiğe düşmeli.
        return try {
            personalized(viewerId, cursor as? Personalized, limit, context, variant)
        } catch (error: ApiException) {
            throw error
        } catch (error: Throwable) {
            logger.error("Personalized feed failed, falling back to chronological", error)
            recordFallback(
                viewerId,
                FallbackReason.RANKING_ERROR,
                variant,
                "${error::class.simpleName}: ${error.message}",
            )
            // Hata yolu bilerek kayitsiz: burayi kontrol kolu gibi yazmak
            // ariza trafigini temel cizgiye karistirirdi.
            chronological(viewerId, null, limit, context = null, recordControl = false)
        }
    }

    private fun recordFallback(
        viewerId: UUID,
        reason: FallbackReason,
        variant: FeedVariant,
        detail: String?,
    ) {
        runCatching {
            fallbacks.record(
                FeedFallback(
                    userId = viewerId,
                    occurredAt = clock.instant(),
                    reason = reason,
                    modelVersion = ranker.modelVersion,
                    experimentVariant = variant.wireName,
                    detail = detail,
                )
            )
        }.onFailure {
            // Kaydın kendisi patlarsa akışı düşürmenin anlamı yok; zaten
            // yedek yoldayız.
            logger.warn("Could not record feed fallback: reason={}", reason, it)
        }
    }

    // ------------------------------------------------------ kişiselleştirme

    private fun personalized(
        viewerId: UUID,
        cursor: Personalized?,
        limit: Int,
        requested: FeedRecommendationContext?,
        variant: FeedVariant,
    ): FeedResponse {
        val now = clock.instant()
        val rankedAt = cursor?.rankedAt ?: now
        val offset = cursor?.offset ?: 0
        val context = cursor?.context() ?: requested ?: FeedRecommendationContext(
            localHour = now.atZone(ZoneOffset.UTC).hour,
            timezoneOffsetMinutes = 0,
            sessionId = UUID.randomUUID(),
        )

        val startedAt = System.nanoTime()
        val pool = gather(viewerId, rankedAt)
        if (pool.isEmpty()) {
            recordFallback(viewerId, FallbackReason.NO_CANDIDATES, variant, null)
            return FeedResponse(emptyList(), null, modelVersion = ranker.modelVersion)
        }

        // Aday sorguları medya ve konuları getirmiyor. Sıralayıcı konu
        // yakınlığını `details.topics` üzerinden okuduğu için hidrasyon
        // sıralamadan önce olmak zorunda; sonraya bırakılsaydı her gönderi
        // konusuz görünür ve konu tercihi sıralamayı hiç etkilemezdi.
        val hydrated = posts.hydrate(pool.map { it.details })
        val sourceByPost = pool.mapIndexed { index, candidate ->
            hydrated[index].post.id to candidate.source
        }.toMap()

        val signals = recommendations.recentSignals(viewerId, SIGNAL_LIMIT, rankedAt)
        val objectives = variant.objectives ?: RankingObjectives.DEFAULT
        val result = try {
            ranker.rankAll(viewerId, hydrated, signals, context, rankedAt, objectives)
        } catch (error: Throwable) {
            // Hedef yapılandırması sıralamayı bozarsa varsayılana dönüyoruz.
            // Kronolojiğe düşmek gereksiz sert olurdu: aday havuzu ve profil
            // sağlam, bozulan yalnızca ağırlıklar.
            logger.warn("Objectives failed for {}, falling back to defaults", variant.wireName, error)
            recordFallback(viewerId, FallbackReason.OBJECTIVES_ERROR, variant, error.message)
            ranker.rankAll(viewerId, hydrated, signals, context, rankedAt, RankingObjectives.DEFAULT)
        }
        val ranked = result.ranked

        val page = ranked.drop(offset).take(limit)
        if (page.isEmpty()) return FeedResponse(emptyList(), null, modelVersion = ranker.modelVersion)

        val requestId = UUID.randomUUID()
        recordServed(viewerId, requestId, context, page, sourceByPost, offset, now)
        val durationMillis = ((System.nanoTime() - startedAt) / 1_000_000).toInt()
        recordLineage(
            viewerId, requestId, context, ranked, page, sourceByPost, offset, rankedAt, result,
            variant = variant, shadowOf = null, durationMillis = durationMillis,
        )
        recordShadow(viewerId, requestId, context, hydrated, sourceByPost, rankedAt, signals)

        val hasMore = offset + page.size < ranked.size
        return FeedResponse(
            items = page.map { rankedPost ->
                rankedPost.details.toResponse(storage, mediaUrlExpiry).copy(
                    recommendationReason = rankedPost.reason,
                    candidateSource = sourceByPost[rankedPost.details.post.id]?.name,
                )
            },
            nextCursor = if (hasMore) {
                encodeCursor(Personalized(rankedAt, offset + page.size, context))
            } else {
                null
            },
            requestId = requestId.toString(),
            modelVersion = ranker.modelVersion,
        )
    }

    /**
     * Aday kaynakları [CandidateSource] sırasına göre gezilir ve gönderiler
     * tekilleştirilir; bir gönderi birden çok kaynağa uyuyorsa listedeki ilk
     * kaynak kazanır.
     *
     * Takip en başta olduğu için havuz dolsa bile takip içeriği elenmez —
     * eskiden aday havuzu "sistemdeki en yeni 200 gönderi" olduğu için takip
     * ettiğin birinin biraz eski gönderisi akışta hiç görünmüyordu.
     */
    private fun gather(viewerId: UUID, rankedAt: Instant): List<Candidate> {
        val seen = mutableSetOf<UUID>()
        val pool = mutableListOf<Candidate>()
        for (source in CandidateSource.GENERATED) {
            if (pool.size >= MAX_POOL) break
            val rows = runCatching {
                posts.candidates(viewerId, source, TopicService.PRIORITY_TOPIC_COUNT, rankedAt, PER_SOURCE)
            }.getOrElse {
                logger.warn("Candidate source failed: source={}", source, it)
                emptyList()
            }
            for (details in rows) {
                if (pool.size >= MAX_POOL) break
                if (seen.add(details.post.id)) pool += Candidate(details, source)
            }
        }
        return pool
    }

    /**
     * Değerlendirilen bütün adayları, puanlarıyla ve istek anındaki etkileşim
     * sayaçlarıyla yazar.
     *
     * Sayaçlar burada dondurulmasa eğitim verisi `posts` tablosundan
     * okunurdu ve geleceğin beğenileri geçmiş bir isteğe sızardı — model
     * kendi sonucunu girdi olarak görürdü.
     */
    /**
     * Kronolojik kontrol kolunun soy kütüğü.
     *
     * Aday üretimi yok, bu yüzden havuz gösterilen sayfanın kendisi; puan da
     * yok. Sürüm alanları `chronological`/`none` yazılıyor ki rapor okurken
     * kontrol kolu bir modelmiş gibi görünmesin.
     */
    private fun recordChronologicalLineage(
        viewerId: UUID,
        items: List<PostDetails>,
        context: FeedRecommendationContext,
        startedAt: Instant,
    ) {
        val now = clock.instant()
        runCatching {
            lineage.record(
                FeedRequestRecord(
                    id = UUID.randomUUID(),
                    userId = viewerId,
                    sessionId = context.sessionId,
                    requestedAt = now,
                    modelVersion = "chronological",
                    policyVersion = POLICY_VERSION,
                    featureVersion = "none",
                    experimentVariant = FeedVariant.CONTROL.wireName,
                    localHour = context.localHour,
                    timezoneOffsetMinutes = context.timezoneOffsetMinutes,
                    personalized = false,
                    durationMillis = Duration.between(startedAt, now).toMillis().toInt(),
                    shadowOf = null,
                    candidates = items.mapIndexed { index, details ->
                        FeedCandidateRecord(
                            postId = details.post.id,
                            rank = index,
                            source = CandidateSource.CHRONOLOGICAL,
                            rawScore = null,
                            finalScore = null,
                            position = index,
                            reason = null,
                            likeCount = details.likeCount,
                            commentCount = details.commentCount,
                            ageHours = Duration.between(details.post.createdAt, now).toMinutes()
                                .coerceAtLeast(0) / 60.0,
                            mediaType = mediaTypeOf(details),
                            topicSlugs = details.topics.map { it.slug },
                        )
                    },
                    affinities = emptyMap(),
                    signalCount = 0,
                )
            )
        }.onFailure {
            logger.warn("Could not record chronological lineage for {}", viewerId, it)
        }
    }

    private fun recordLineage(
        viewerId: UUID,
        requestId: UUID,
        context: FeedRecommendationContext,
        ranked: List<com.nexi.recommendations.RankedPost>,
        page: List<com.nexi.recommendations.RankedPost>,
        sourceByPost: Map<UUID, CandidateSource>,
        offset: Int,
        rankedAt: Instant,
        result: com.nexi.recommendations.RankingResult,
        variant: FeedVariant,
        shadowOf: UUID?,
        durationMillis: Int? = null,
    ) {
        val servedPositions = page.withIndex().associate { (index, item) ->
            item.details.post.id to (offset + index)
        }
        runCatching {
            lineage.record(
                FeedRequestRecord(
                    id = requestId,
                    userId = viewerId,
                    sessionId = context.sessionId,
                    requestedAt = rankedAt,
                    modelVersion = ranker.modelVersion,
                    policyVersion = POLICY_VERSION,
                    featureVersion = ranker.featureVersion,
                    experimentVariant = variant.wireName,
                    localHour = context.localHour,
                    timezoneOffsetMinutes = context.timezoneOffsetMinutes,
                    personalized = true,
                    durationMillis = durationMillis,
                    shadowOf = shadowOf,
                    candidates = ranked.mapIndexed { rank, item ->
                        val post = item.details.post
                        FeedCandidateRecord(
                            postId = post.id,
                            rank = rank,
                            source = sourceByPost[post.id] ?: CandidateSource.DISCOVERY,
                            rawScore = item.rawScore,
                            finalScore = item.score,
                            position = servedPositions[post.id],
                            reason = item.reason,
                            likeCount = item.details.likeCount,
                            commentCount = item.details.commentCount,
                            ageHours = Duration.between(post.createdAt, rankedAt).toMinutes()
                                .coerceAtLeast(0) / 60.0,
                            mediaType = mediaTypeOf(item.details),
                            topicSlugs = item.details.topics.map { it.slug },
                        )
                    },
                    affinities = result.affinities,
                    signalCount = result.signalCount,
                )
            )
        }.onFailure {
            // Soy kütüğü kaydı kaybı akışı düşürmeyi hak etmez.
            logger.warn("Could not record feed lineage: request={}", requestId, it)
        }
    }

    /**
     * Gölge koşusu: başka bir kolun sıralamasını hesaplar, kullanıcıya
     * göstermez, yalnızca kaydeder.
     *
     * Aynı aday kümesi ve aynı profil anlık görüntüsü kullanılıyor. Ayrı bir
     * istek olarak koşsaydı havuz da profil de farklı olur, ve iki sıralama
     * arasındaki farkın modelden mi girdiden mi geldiği söylenemezdi.
     *
     * Gölge hatası asıl akışı etkilemez; kullanıcı ölçüm yüzünden boş ekran
     * görmemeli.
     */
    private fun recordShadow(
        viewerId: UUID,
        servedRequestId: UUID,
        context: FeedRecommendationContext,
        hydrated: List<PostDetails>,
        sourceByPost: Map<UUID, CandidateSource>,
        rankedAt: Instant,
        signals: List<com.nexi.recommendations.RecommendationSignal>,
    ) {
        val shadow = experiments.shadowFor(viewerId) ?: return
        val shadowObjectives = shadow.objectives ?: return
        runCatching {
            // Gölge kolu ayrı bir model değil, aynı sıralayıcının farklı hedef
            // ağırlıkları. Bu sayede karşılaştırma ikinci bir model yüklemeden
            // çalışıyor; eğitilmiş model geldiğinde burası onun sıralamasını
            // çağıracak.
            val result = ranker.rankAll(viewerId, hydrated, signals, context, rankedAt, shadowObjectives)
            recordLineage(
                viewerId = viewerId,
                requestId = UUID.randomUUID(),
                context = context,
                ranked = result.ranked,
                // Gölge gösterilmiyor; hiçbir adayın konumu yok.
                page = emptyList(),
                sourceByPost = sourceByPost,
                offset = 0,
                rankedAt = rankedAt,
                result = result,
                variant = shadow,
                shadowOf = servedRequestId,
            )
        }.onFailure {
            logger.warn("Shadow ranking failed: request={} variant={}", servedRequestId, shadow, it)
        }
    }

    private fun mediaTypeOf(details: PostDetails): String = when {
        details.media.any { it.mimeType.startsWith("video/") } -> "video"
        details.media.isNotEmpty() -> "image"
        else -> "text"
    }

    private fun recordServed(
        viewerId: UUID,
        requestId: UUID,
        context: FeedRecommendationContext,
        page: List<com.nexi.recommendations.RankedPost>,
        sourceByPost: Map<UUID, CandidateSource>,
        offset: Int,
        now: Instant,
    ) {
        runCatching {
            recommendations.append(
                page.mapIndexed { index, rankedPost ->
                    RecommendationEvent(
                        id = UUID.randomUUID(),
                        userId = viewerId,
                        postId = rankedPost.details.post.id,
                        clientEventId = UUID.randomUUID(),
                        sessionId = context.sessionId,
                        feedRequestId = requestId,
                        eventType = RecommendationEventType.FEED_SERVED,
                        surface = "feed",
                        position = (offset + index).coerceAtMost(MAX_RECORDED_POSITION),
                        dwellMillis = null,
                        completionRatio = null,
                        localHour = context.localHour,
                        timezoneOffsetMinutes = context.timezoneOffsetMinutes,
                        targetFeature = null,
                        occurredAt = now,
                        receivedAt = now,
                        platform = EventPlatform.BACKEND,
                        candidateSource = sourceByPost[rankedPost.details.post.id],
                    )
                }
            )
        }.onFailure {
            // Sunum kaydı kaybı akışı düşürmeyi hak etmez; kullanıcı içeriği görsün.
            logger.warn("Could not record served feed: request={}", requestId, it)
        }
    }

    // ---------------------------------------------------------- kronolojik

    private fun chronological(
        viewerId: UUID,
        cursor: Chronological?,
        limit: Int,
        context: FeedRecommendationContext?,
        recordControl: Boolean,
    ): FeedResponse {
        val startedAt = clock.instant()
        val details = posts.feed(viewerId, cursor?.let { FeedCursor(it.createdAt, it.id) }, limit + 1)
        val hasMore = details.size > limit
        val items = details.take(limit)
        // Kontrol kolu da olculebilmeli. Yalnizca `feed_fallbacks`'e yazmak
        // temel cizgiyi butun karsilastirma raporlarindan disarida birakiyordu:
        // kollari kiyaslarken kiyaslanacak taban yoktu.
        if (recordControl && context != null) {
            recordChronologicalLineage(viewerId, items, context, startedAt)
        }
        return FeedResponse(
            items = items.map { it.toResponse(storage, mediaUrlExpiry) },
            nextCursor = if (hasMore) {
                items.lastOrNull()?.post?.let { encodeCursor(Chronological(it.createdAt, it.id)) }
            } else {
                null
            },
        )
    }

    private data class Candidate(val details: PostDetails, val source: CandidateSource)

    // --------------------------------------------------------------- imleç

    private sealed interface Cursor

    private data class Chronological(val createdAt: Instant, val id: UUID) : Cursor

    private data class Personalized(
        val rankedAt: Instant,
        val offset: Int,
        val localHour: Int,
        val timezoneOffsetMinutes: Int,
        val sessionId: UUID,
    ) : Cursor {
        constructor(rankedAt: Instant, offset: Int, context: FeedRecommendationContext) :
            this(rankedAt, offset, context.localHour, context.timezoneOffsetMinutes, context.sessionId)

        fun context() = FeedRecommendationContext(localHour, timezoneOffsetMinutes, sessionId)
    }

    /**
     * İmleç iki biçim taşıyor, bu yüzden başında tür harfi var. Saat bağlamı da
     * imleçte: ikinci sayfa başka bir saat diliminde istenirse sıralama
     * değişir ve aynı gönderi tekrar çıkardı.
     */
    private fun encodeCursor(cursor: Cursor): String {
        val raw = when (cursor) {
            is Chronological -> "C|${cursor.createdAt}|${cursor.id}"
            is Personalized -> with(cursor) {
                "P|$rankedAt|$offset|$localHour|$timezoneOffsetMinutes|$sessionId"
            }
        }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.toByteArray(StandardCharsets.UTF_8))
    }

    private fun decodeCursor(raw: String): Cursor = try {
        val parts = String(Base64.getUrlDecoder().decode(raw), StandardCharsets.UTF_8).split('|')
        when (parts[0]) {
            "C" -> Chronological(Instant.parse(parts[1]), UUID.fromString(parts[2]))
            "P" -> Personalized(
                rankedAt = Instant.parse(parts[1]),
                offset = parts[2].toInt().also { require(it in 0..MAX_OFFSET) },
                localHour = parts[3].toInt().also { require(it in 0..23) },
                timezoneOffsetMinutes = parts[4].toInt().also { require(it in -840..840) },
                sessionId = UUID.fromString(parts[5]),
            )
            else -> error("bilinmeyen imleç türü")
        }
    } catch (_: Throwable) {
        throw ApiException(HttpStatusCode.BadRequest, "INVALID_CURSOR", "Akış imleci geçersiz.", "cursor")
    }

    companion object {
        /**
         * Aday uretimi ve eleme kurallarinin surumu.
         *
         * Modelden ayri tutuluyor: siralama modeli hic degismeden aday
         * kaynagi eklenirse akis baska sonuc verir, ve iki kosuyu
         * karsilastirirken bunun gorunmesi gerekir.
         */
        const val POLICY_VERSION = "nexi-policy-v2"

        const val DEFAULT_LIMIT = 20
        const val MAX_LIMIT = 50

        /** Her aday kaynağından okunacak en fazla gönderi. */
        const val PER_SOURCE = 60

        /**
         * Havuzun üst sınırı. Sayfalama havuzun içinde ilerlediği için bu aynı
         * zamanda kişiselleştirilmiş akışın derinliği: 300 aday, 20'lik
         * sayfalarla 15 sayfa eder. Sonrası için istemci yeni bir oturum açar.
         */
        const val MAX_POOL = 300
        const val SIGNAL_LIMIT = 2_000

        private const val MAX_OFFSET = MAX_POOL
        /** `recommendation_events.position` kolonundaki CHECK sınırı. */
        private const val MAX_RECORDED_POSITION = 999
    }
}
