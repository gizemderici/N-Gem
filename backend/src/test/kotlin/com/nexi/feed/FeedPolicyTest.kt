package com.nexi.feed

import com.nexi.auth.ApiException
import com.nexi.posts.CandidateSource
import com.nexi.posts.CreatePostRequest
import com.nexi.posts.FakeObjectStorage
import com.nexi.posts.InMemoryPostRepository
import com.nexi.posts.PostService
import com.nexi.recommendations.ContextualRanker
import com.nexi.recommendations.FeedRecommendationContext
import com.nexi.recommendations.RecommendationEvent
import com.nexi.recommendations.RecommendationExport
import com.nexi.recommendations.RecommendationEventType
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
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FeedPolicyTest {
    private val now = Instant.parse("2026-08-23T12:00:00Z")
    private val clock = TestClock(now)
    private val viewerId = UUID.randomUUID()
    private val followedId = UUID.randomUUID()

    private val topics = InMemoryTopicRepository()
    private val postRepository = InMemoryPostRepository(viewerId, now, topics)
    private val events = RecordingRepository()
    private val storage = FakeObjectStorage()
    private val posts = PostService(postRepository, storage, clock)
    private val policy = FeedPolicy(postRepository, events, ContextualRanker(), storage, clock)

    private val context = FeedRecommendationContext(12, 180, UUID.randomUUID())

    private fun publish(authorId: UUID = viewerId, text: String = "Gönderi", vararg topicSlugs: String) =
        UUID.fromString(
            posts.create(
                authorId,
                CreatePostRequest(text = text, topicIds = topicSlugs.map { topics.topic(it).id.toString() }),
            ).id
        )

    // ------------------------------------------------------------ sayfalama

    @Test
    fun `the personalized feed pages instead of stopping after one screen`() {
        // Eskiden kişiselleştirilmiş akış nextCursor'u null döndürüyordu:
        // kullanıcı ilk ekranın sonuna gelince akış duruyordu.
        repeat(7) { publish(text = "Gönderi $it") }

        val first = policy.feed(viewerId, null, 3, context)
        assertEquals(3, first.items.size)
        val cursor = assertNotNull(first.nextCursor, "kişiselleştirilmiş akış devam edebilmeli")

        val second = policy.feed(viewerId, cursor, 3, context)
        assertEquals(3, second.items.size)
        val third = policy.feed(viewerId, assertNotNull(second.nextCursor), 3, context)

        val ids = (first.items + second.items + third.items).map { it.id }
        assertEquals(7, ids.size)
        assertEquals(ids.distinct().size, ids.size, "hiçbir gönderi iki sayfada çıkmamalı")
        assertNull(third.nextCursor)
    }

    @Test
    fun `a post written between two pages does not shift the ranking`() {
        repeat(4) { publish(text = "Gönderi $it") }
        val first = policy.feed(viewerId, null, 2, context)
        val cursor = assertNotNull(first.nextCursor)

        // Aday kümesi ilk sayfada donduruldu; sonradan yazılan gönderi araya
        // girip sırayı kaydırmamalı.
        clock.advanceHours(1)
        publish(text = "Sonradan gelen")

        val second = policy.feed(viewerId, cursor, 2, context)
        val ids = (first.items + second.items).map { it.id }
        assertEquals(ids.distinct().size, ids.size)
        assertTrue(second.items.none { it.text == "Sonradan gelen" })
    }

    @Test
    fun `the same request produces the same order`() {
        repeat(5) { publish(text = "Gönderi $it") }

        val once = policy.feed(viewerId, null, 5, context).items.map { it.id }
        val twice = policy.feed(viewerId, null, 5, context).items.map { it.id }

        assertEquals(once, twice, "sıralama tekrar üretilebilir olmalı")
    }

    @Test
    fun `a broken cursor is refused`() {
        assertEquals("INVALID_CURSOR", assertFailsWith<ApiException> {
            policy.feed(viewerId, "bozuk-imlec", 5, context)
        }.code)
    }

    // ------------------------------------------------------- aday kaynaklari

    @Test
    fun `content from a followed author never disappears from the feed`() {
        // Aday havuzu bir zamanlar "sistemdeki en yeni 200 gönderi" idi;
        // takip edilen birinin biraz eski gönderisi akışta hiç görünmüyordu.
        val followed = publish(followedId, "Takip ettigim kisinin gonderisi")
        postRepository.follows += viewerId to followedId
        repeat(40) { publish(UUID.randomUUID(), "Yabanci gonderi $it") }

        val page = policy.feed(viewerId, null, 50, context)

        val item = page.items.single { it.id == followed.toString() }
        assertEquals(CandidateSource.FOLLOWING.name, item.candidateSource)
    }

    @Test
    fun `each item reports the source it was drawn from`() {
        postRepository.follows += viewerId to followedId
        publish(followedId, "Takip")
        publish(UUID.randomUUID(), "Yabanci")

        val sources = policy.feed(viewerId, null, 20, context).items.mapNotNull { it.candidateSource }

        assertEquals(2, sources.size)
        assertTrue(CandidateSource.FOLLOWING.name in sources)
        assertTrue(sources.all { it in CandidateSource.entries.map(CandidateSource::name) })
    }

    @Test
    fun `a post is counted once even when several sources match it`() {
        // Az takipçili bir üreticinin popüler gönderisi hem POPULAR hem
        // NEW_CREATOR kaynağına uyuyor; akışta bir kez çıkmalı.
        val authorId = UUID.randomUUID()
        val postId = publish(authorId, "Cok begenilen gonderi")
        repeat(4) { postRepository.setLike(postId, UUID.randomUUID(), true, now) }

        val ids = policy.feed(viewerId, null, 20, context).items.map { it.id }

        assertEquals(1, ids.count { it == postId.toString() })
    }

    // -------------------------------------------------------------- gizleme

    @Test
    fun `a hidden post never enters any candidate pool`() {
        val hidden = publish(text = "Gizlenen")
        publish(text = "Kalan")
        postRepository.hide(viewerId, hidden)

        val ids = policy.feed(viewerId, null, 20, context).items.map { it.id }

        assertTrue(hidden.toString() !in ids, "gizlenen gönderi tekrar gösterilmemeli")
        assertEquals(1, ids.size)
    }

    // ---------------------------------------------------------- sunum kaydi

    @Test
    fun `serving writes one record per item with its source and position`() {
        repeat(3) { publish(text = "Gönderi $it") }

        val first = policy.feed(viewerId, null, 2, context)
        policy.feed(viewerId, assertNotNull(first.nextCursor), 2, context)

        val served = events.written.filter { it.eventType == RecommendationEventType.FEED_SERVED }
        assertEquals(3, served.size)
        assertEquals(listOf(0, 1, 2), served.map { it.position })
        assertTrue(served.all { it.candidateSource != null }, "aday kaynağı kayda geçmeli")
        assertEquals(1, served.map { it.feedRequestId }.distinct().size.let { if (it >= 1) 1 else 0 })
    }

    @Test
    fun `personalization off means no profile read and no served record`() {
        repeat(3) { publish(text = "Gönderi $it") }

        val page = policy.feed(viewerId, null, 2, context, personalizationEnabled = false)

        assertEquals(2, page.items.size)
        assertNotNull(page.nextCursor)
        assertNull(page.modelVersion)
        assertTrue(events.written.isEmpty(), "kişiselleştirme kapalıyken sunum olayı yazılmamalı")
        assertTrue(page.items.all { it.candidateSource == null })
    }

    @Test
    fun `the chronological cursor keeps working when personalization is off`() {
        repeat(5) { publish(text = "Gönderi $it") }

        val first = policy.feed(viewerId, null, 2, context, personalizationEnabled = false)
        val second = policy.feed(
            viewerId,
            assertNotNull(first.nextCursor),
            2,
            context,
            personalizationEnabled = false,
        )

        val ids = (first.items + second.items).map { it.id }
        assertEquals(ids.distinct().size, ids.size)
    }

    @Test
    fun `an empty catalog produces an empty feed rather than an error`() {
        val page = policy.feed(viewerId, null, 10, context)

        assertTrue(page.items.isEmpty())
        assertNull(page.nextCursor)
    }
}

/**
 * İlerletilebilir saat.
 *
 * Bellek içi depo her yeni gönderiye bir saniye *daha eski* zaman damgası
 * veriyor (sıralamanın deterministik kalması için). Dondurma sınırının
 * gerçekten çalıştığını görmek için saati ileri almak şart, yoksa sonradan
 * yazılan gönderi de sınırın altında kalıyor.
 */
private class TestClock(private var current: Instant) : Clock() {
    fun advanceHours(hours: Long) {
        current = current.plusSeconds(hours * 3_600)
    }

    override fun getZone(): java.time.ZoneId = ZoneOffset.UTC
    override fun withZone(zone: java.time.ZoneId?): Clock = this
    override fun instant(): Instant = current
}

private class RecordingRepository : RecommendationRepository {
    val written = mutableListOf<RecommendationEvent>()
    override val personalizationAvailable = true

    override fun append(events: List<RecommendationEvent>): Int {
        written += events
        return events.size
    }

    override fun recentSignals(userId: UUID, limit: Int, notAfter: Instant?) = emptyList<RecommendationSignal>()
    override fun profileStats(userId: UUID) = RecommendationProfileStats(written.size.toLong(), null)
    override fun clear(userId: UUID) = written.clear()
    override fun export(userId: UUID) = RecommendationExport()
    override fun deleteOlderThan(cutoff: Instant, batchSize: Int) = 0
    override fun hide(userId: UUID, postId: UUID, now: Instant) = Unit
}
