package com.nexi.feed

import com.nexi.posts.CandidateSource
import com.nexi.posts.CreatePostRequest
import com.nexi.posts.FakeObjectStorage
import com.nexi.posts.FeedCursor
import com.nexi.posts.InMemoryPostRepository
import com.nexi.posts.PostDetails
import com.nexi.posts.PostRepository
import com.nexi.posts.PostService
import com.nexi.recommendations.ContextualRanker
import com.nexi.recommendations.FeedRecommendationContext
import com.nexi.recommendations.RecommendationEvent
import com.nexi.recommendations.RecommendationEventType
import com.nexi.recommendations.RecommendationExport
import com.nexi.recommendations.RecommendationProfileStats
import com.nexi.recommendations.RecommendationRepository
import com.nexi.recommendations.RecommendationSignal
import com.nexi.topics.InMemoryTopicRepository
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Gölge koşusu, öldürme anahtarı ve hata yedeği — Faz 6'nın kabul kriterleri.
 */
class FeedShadowTest {
    private val now = Instant.parse("2026-08-23T12:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val viewerId = UUID.randomUUID()

    private val topics = InMemoryTopicRepository()
    private val postRepository = InMemoryPostRepository(viewerId, now, topics)
    private val lineage = RecordingLineage()
    private val storage = FakeObjectStorage()
    private val posts = PostService(postRepository, storage, clock)
    private val context = FeedRecommendationContext(12, 180, UUID.randomUUID())

    private fun policy(
        config: FeedExperimentConfig,
        repository: PostRepository = postRepository,
    ) = FeedPolicy(
        posts = repository,
        recommendations = AvailableRepository,
        ranker = ContextualRanker(),
        storage = storage,
        clock = clock,
        lineage = lineage,
        experiments = FeedExperiments(config),
    )

    private fun publish(text: String) = posts.create(viewerId, CreatePostRequest(text = text)).id

    @Test
    fun `the served request is recorded with the arm that actually ranked it`() {
        repeat(3) { publish("Gönderi $it") }

        val page = policy(
            FeedExperimentConfig(weights = mapOf(FeedVariant.HEURISTIC to 1))
        ).feed(viewerId, null, 2, context)

        assertEquals(2, page.items.size)
        val servedId = UUID.fromString(assertNotNull(page.requestId))

        val served = lineage.recorded.single()
        assertEquals(servedId, served.id)
        // Etiket gercekten siralamayi yapan kol olmali; yanlis etiket sonraki
        // kol karsilastirmasini sessizce anlamsizlastirirdi.
        assertEquals(FeedVariant.HEURISTIC.wireName, served.experimentVariant)
        assertEquals(2, served.returnedCount)
        assertNull(served.shadowOf)
    }

    @Test
    fun `no configuration can produce a shadow run today`() {
        // Golge yazma yolu bugun erisilemez ve bunu gizlemek yerine yaziyoruz.
        // CONTROL kolu kisisellestirmeden once donuyor, HEURISTIC ile ayni kol
        // golgelenmiyor, ve LEARNED yapilandirmada reddediliyor. Ikinci bir
        // siralayici geldiginde (Demo Faz 16) yol canlanacak.
        repeat(3) { publish("Gönderi $it") }

        listOf(
            FeedExperimentConfig(weights = mapOf(FeedVariant.HEURISTIC to 1), shadow = FeedVariant.HEURISTIC),
            FeedExperimentConfig(weights = mapOf(FeedVariant.CONTROL to 1), shadow = FeedVariant.HEURISTIC),
        ).forEach { config ->
            lineage.recorded.clear()
            policy(config).feed(viewerId, null, 2, context)
            assertTrue(lineage.recorded.none { it.shadowOf != null }, "golge kaydi beklenmiyor: $config")
        }
    }

    @Test
    fun `no shadow run happens when the arms are the same`() {
        publish("Gönderi")

        policy(
            FeedExperimentConfig(
                weights = mapOf(FeedVariant.HEURISTIC to 1),
                shadow = FeedVariant.HEURISTIC,
            )
        ).feed(viewerId, null, 5, context)

        assertTrue(lineage.recorded.none { it.shadowOf != null })
    }

    @Test
    fun `the kill switch turns the feed chronological with one setting`() {
        repeat(3) { publish("Gönderi $it") }

        val page = policy(
            FeedExperimentConfig(enabled = false, shadow = FeedVariant.HEURISTIC)
        ).feed(viewerId, null, 2, context)

        assertEquals(2, page.items.size)
        assertNull(page.modelVersion, "profil okunmamalı")
        assertNull(page.requestId)
        assertTrue(lineage.recorded.isEmpty())
    }

    @Test
    fun `the control arm gets the chronological feed`() {
        repeat(3) { publish("Gönderi $it") }

        val page = policy(
            FeedExperimentConfig(weights = mapOf(FeedVariant.CONTROL to 1))
        ).feed(viewerId, null, 2, context)

        assertNull(page.modelVersion)
        assertTrue(lineage.recorded.isEmpty())
    }

    @Test
    fun `a ranking failure falls back to the chronological feed`() {
        // Kabul kriteri: model hatasinda otomatik donus. Kullanici bozuk bir
        // model yuzunden bos ekran gormemeli.
        repeat(3) { publish("Gönderi $it") }

        val page = policy(
            FeedExperimentConfig(weights = mapOf(FeedVariant.HEURISTIC to 1)),
            repository = FailingHydrateRepository(postRepository),
        ).feed(viewerId, null, 2, context)

        assertEquals(2, page.items.size)
        assertNull(page.modelVersion, "yedek yol kronolojik")
        assertNotNull(page.nextCursor)
    }

    @Test
    fun `a broken cursor still returns a validation error, not a silent fallback`() {
        // Yedek yol her hatayi yutmamali: gecersiz imlec istemcinin hatasi ve
        // sessizce baska bir sayfa dondurmek onu gizlerdi.
        publish("Gönderi")

        val error = kotlin.runCatching {
            policy(FeedExperimentConfig()).feed(viewerId, "bozuk", 2, context)
        }.exceptionOrNull()

        assertEquals("INVALID_CURSOR", (error as? com.nexi.auth.ApiException)?.code)
    }
}

private class RecordingLineage : FeedLineageRepository {
    val recorded = mutableListOf<FeedRequestRecord>()
    override fun record(request: FeedRequestRecord) {
        recorded += request
    }
}

/** Sıralamanın ortasında patlayan depo; yedek yolu sınamak için. */
private class FailingHydrateRepository(private val delegate: PostRepository) :
    PostRepository by delegate {
    override fun hydrate(details: List<PostDetails>): List<PostDetails> =
        throw IllegalStateException("hidrasyon bozuk")

    override fun feed(viewerId: UUID, cursor: FeedCursor?, limit: Int): List<PostDetails> =
        delegate.feed(viewerId, cursor, limit)

    override fun candidates(
        viewerId: UUID,
        source: CandidateSource,
        priorityTopicCount: Int,
        notAfter: Instant,
        limit: Int,
    ): List<PostDetails> = delegate.candidates(viewerId, source, priorityTopicCount, notAfter, limit)
}

/** Sinyal döndürebilen depo; hedeflerin farkı ancak sinyalle görünüyor. */
private class SignallingRepository : RecommendationRepository {
    var rows: List<RecommendationSignal> = emptyList()
    override val personalizationAvailable = true
    override fun append(events: List<RecommendationEvent>) = events.size
    override fun recentSignals(userId: UUID, limit: Int, notAfter: Instant?) = rows
    override fun profileStats(userId: UUID) = RecommendationProfileStats(rows.size.toLong(), null)
    override fun clear(userId: UUID) = Unit
    override fun export(userId: UUID) = RecommendationExport()
    override fun deleteOlderThan(cutoff: Instant, batchSize: Int) = 0
    override fun hide(userId: UUID, postId: UUID, now: Instant) = Unit
}

private object AvailableRepository : RecommendationRepository {
    override val personalizationAvailable = true
    override fun append(events: List<RecommendationEvent>) = events.size
    override fun recentSignals(userId: UUID, limit: Int, notAfter: Instant?) = emptyList<RecommendationSignal>()
    override fun profileStats(userId: UUID) = RecommendationProfileStats(0, null)
    override fun clear(userId: UUID) = Unit
    override fun export(userId: UUID) = RecommendationExport()
    override fun deleteOlderThan(cutoff: Instant, batchSize: Int) = 0
    override fun hide(userId: UUID, postId: UUID, now: Instant) = Unit
}

/**
 * Gölge artık gerçekten koşuyor.
 *
 * Faz 11'de gölge yolunun hiç erişilemediği ortaya çıkmıştı: `control`
 * kişiselleştirmeden önce dönüyor, `heuristic` kendisini gölgeleyemiyor ve
 * `learned` reddediliyordu. Kolları ayrı bir model yerine aynı sıralayıcının
 * farklı **hedef ağırlıkları** yapınca yol canlandı.
 */
class FeedObjectiveShadowTest {
    private val now = Instant.parse("2026-08-23T12:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val viewerId = UUID.randomUUID()

    private val topics = InMemoryTopicRepository()
    private val postRepository = InMemoryPostRepository(viewerId, now, topics)
    private val lineage = RecordingLineage()
    private val storage = FakeObjectStorage()
    private val posts = PostService(postRepository, storage, clock)
    private val context = FeedRecommendationContext(12, 180, UUID.randomUUID())

    private val signals = SignallingRepository()

    private fun policy(config: FeedExperimentConfig) = FeedPolicy(
        posts = postRepository,
        recommendations = signals,
        ranker = ContextualRanker(),
        storage = storage,
        clock = clock,
        lineage = lineage,
        experiments = FeedExperiments(config),
    )

    private fun publish(author: UUID, text: String, vararg topicSlugs: String) = posts.create(
        author,
        CreatePostRequest(text = text, topicIds = topicSlugs.map { topics.topic(it).id.toString() }),
    ).id

    /**
     * Kullanıcının gizlediği konudan taze ve popüler bir gönderi.
     *
     * Güvenlik hedefinin farkı ancak burada görünüyor: olumsuz yakınlık
     * kişiselleştirme bileşeninde özellik sayısının kareköküne bölündüğü
     * için uzun metinli bir gönderide seyreliyor.
     */
    private fun seedPosts() {
        val disliked = UUID.fromString(
            publish(
                UUID.randomUUID(),
                "Sahilde uzun bir yürüyüş sonrası kahve içtim ve manzara gerçekten " +
                    "harikaydı, akşam üzeri hava serinleyince tekrar çıkıp biraz daha " +
                    "dolaşmaya karar verdim",
                "seyahat",
            )
        )
        repeat(4) { postRepository.setLike(disliked, UUID.randomUUID(), true, now) }
        repeat(4) { publish(UUID.randomUUID(), "Yazilim ve kodlama uzerine not $it", "teknoloji") }

        signals.rows = listOf(
            RecommendationSignal(
                eventType = RecommendationEventType.CONTENT_HIDDEN,
                postId = UUID.randomUUID(),
                authorId = UUID.randomUUID(),
                body = null,
                topicSlugs = listOf("seyahat"),
                mediaType = "text",
                dwellMillis = null,
                completionRatio = null,
                localHour = 12,
                targetFeature = null,
                occurredAt = now.minusSeconds(7_200),
            )
        )
    }

    @Test
    fun `the shadow arm actually runs and is recorded against the served request`() {
        seedPosts()

        val page = policy(
            FeedExperimentConfig(
                weights = mapOf(FeedVariant.HEURISTIC to 1),
                shadow = FeedVariant.SAFE,
            )
        ).feed(viewerId, null, 5, context)

        val served = lineage.recorded.single { it.shadowOf == null }
        val shadow = lineage.recorded.single { it.shadowOf != null }

        assertEquals(UUID.fromString(assertNotNull(page.requestId)), served.id)
        assertEquals(served.id, shadow.shadowOf)
        assertEquals(FeedVariant.HEURISTIC.wireName, served.experimentVariant)
        assertEquals(FeedVariant.SAFE.wireName, shadow.experimentVariant)
    }

    @Test
    fun `the shadow ranking differs from the served one`() {
        // Gölge aynı sıralamayı üretirse karşılaştırmanın anlamı yok.
        seedPosts()

        policy(
            FeedExperimentConfig(
                weights = mapOf(FeedVariant.HEURISTIC to 1),
                shadow = FeedVariant.SAFE,
            )
        ).feed(viewerId, null, 5, context)

        val served = lineage.recorded.single { it.shadowOf == null }
        val shadow = lineage.recorded.single { it.shadowOf != null }

        // `rank` sıralamanın kendisi; `finalScore` çeşitlendirme öncesi puan
        // olduğu için iki kolda da aynı ve karşılaştırmaya yaramıyor.
        val servedOrder = served.candidates.sortedBy { it.rank }.map { it.postId }
        val shadowOrder = shadow.candidates.sortedBy { it.rank }.map { it.postId }
        // Aynı havuz, aynı an, farklı hedefler.
        assertEquals(servedOrder.toSet(), shadowOrder.toSet())
        assertTrue(servedOrder != shadowOrder, "güvenlik hedefi sırayı değiştirmeli")
    }

    @Test
    fun `the shadow run never reaches the user`() {
        seedPosts()

        val page = policy(
            FeedExperimentConfig(
                weights = mapOf(FeedVariant.HEURISTIC to 1),
                shadow = FeedVariant.SAFE,
            )
        ).feed(viewerId, null, 3, context)

        val shadow = lineage.recorded.single { it.shadowOf != null }
        assertEquals(0, shadow.returnedCount)
        assertTrue(shadow.candidates.all { it.position == null })
        // Kullanıcı gösterilen kolu görüyor.
        assertEquals(3, page.items.size)
    }

    @Test
    fun `each arm ranks with its own objectives`() {
        seedPosts()

        lineage.recorded.clear()
        policy(FeedExperimentConfig(weights = mapOf(FeedVariant.HEURISTIC to 1)))
            .feed(viewerId, null, 5, context)
        val heuristic = lineage.recorded.single().candidates.sortedBy { it.rank }.map { it.postId }

        lineage.recorded.clear()
        policy(FeedExperimentConfig(weights = mapOf(FeedVariant.SAFE to 1)))
            .feed(viewerId, null, 5, context)
        val safe = lineage.recorded.single().candidates.sortedBy { it.rank }.map { it.postId }

        assertTrue(heuristic != safe, "SAFE kolu HEURISTIC'ten farklı sıralamalı")
    }

    @Test
    fun `the recorded rank matches the order the user was shown`() {
        // Gösterilen adaylarda `rank` ile `position` aynı olmalı; ikisi
        // ayrışırsa soy kütüğü akışın sırasını yanlış anlatıyor demektir.
        seedPosts()

        policy(FeedExperimentConfig(weights = mapOf(FeedVariant.HEURISTIC to 1)))
            .feed(viewerId, null, 4, context)

        val served = lineage.recorded.single()
        served.candidates.filter { it.position != null }.forEach { candidate ->
            assertEquals(candidate.position, candidate.rank, "gösterilen adayda rank == position")
        }
        assertEquals(
            (0 until served.candidates.size).toList(),
            served.candidates.map { it.rank }.sorted(),
            "sıra boşluksuz olmalı",
        )
    }

    @Test
    fun `an arm that cannot rank is refused as a shadow`() {
        // CONTROL kronolojik; aday havuzunu yeniden sıralamıyor, bu yüzden
        // gölgelenemez.
        assertFailsWith<IllegalArgumentException> {
            FeedExperimentConfig(
                weights = mapOf(FeedVariant.HEURISTIC to 1),
                shadow = FeedVariant.CONTROL,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            FeedExperimentConfig(
                weights = mapOf(FeedVariant.HEURISTIC to 1),
                shadow = FeedVariant.LEARNED,
            )
        }
    }
}
