package com.nexi.recommendations

import com.nexi.media.MediaAsset
import com.nexi.media.MediaStatus
import com.nexi.posts.Post
import com.nexi.posts.PostAuthorResponse
import com.nexi.posts.PostDetails
import com.nexi.posts.PostStatus
import com.nexi.topics.InMemoryTopicRepository
import com.nexi.topics.Topic
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.UUID
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `nexi-contextual-v1`'in Kotlin ve Python tarafının aynı sıralamayı ürettiğini
 * garanti eden referans dosyasını üretir ve doğrular.
 *
 * İki uygulama vardı ve sessizce ayrışmışlardı: Python değerlendirici güncellik
 * bileşenini hiç hesaplamıyor, kaliteyi başka bir bölenle alıyor, keşif
 * gürültüsünü SHA-256 ile üretiyor ve çeşitlendirmeyi hiç uygulamıyordu. Bu
 * hâliyle "çevrimdışı değerlendirme" başka bir modeli ölçüyordu.
 *
 * Bölüşüm şu: Türkçe kelime ayrıştırması Kotlin'de kalıyor ve çıkardığı
 * özellikler dosyaya yazılıyor; Python bu özelliklerden **aynı aritmetiği**
 * yürütmek zorunda. Python'a tokenizer'ı da yazdırmak, bakımı imkânsız ikinci
 * bir kopya üretirdi.
 *
 * Sıralayıcı değişirse bu test kırılır. Doğru tepki, değişikliği Python'a da
 * taşıyıp dosyayı yeniden üretmek:
 *
 *     gradle test --tests '*RankingParityTest*' -Dnexi.parity.write=true
 */
class RankingParityTest {
    private val ranker = ContextualRanker()
    private val topics = InMemoryTopicRepository()

    private val viewerId = UUID.fromString("11111111-2222-4333-8444-555555555555")
    private val rankedAt = Instant.parse("2026-08-23T21:00:00Z")
    private val context = FeedRecommendationContext(localHour = 21, timezoneOffsetMinutes = 180, sessionId = SESSION)

    @Test
    fun `the Kotlin ranker still matches the checked-in parity fixture`() {
        val candidates = candidates()
        val signals = signals()
        val result = ranker.rankAll(viewerId, candidates, signals, context, rankedAt)

        val fixture = ParityFixture(
            modelVersion = ranker.modelVersion,
            featureVersion = ranker.featureVersion,
            viewerId = viewerId.toString(),
            rankedAtEpochSecond = rankedAt.epochSecond,
            localHour = context.localHour,
            signals = signals.map { it.toFixture() },
            candidates = candidates.map { it.toFixture() },
            expected = result.ranked.mapIndexed { position, item ->
                ExpectedItem(
                    postId = item.details.post.id.toString(),
                    position = position,
                    rawScore = item.rawScore,
                    finalScore = item.score,
                )
            },
            affinities = result.affinities.toSortedMap(),
        )

        val encoded = JSON.encodeToString(fixture) + "\n"
        if (System.getProperty("nexi.parity.write") == "true" || !FIXTURE.exists()) {
            Files.createDirectories(FIXTURE.parent)
            FIXTURE.writeText(encoded)
        }

        assertTrue(FIXTURE.exists(), "referans dosyası bulunamadı: $FIXTURE")
        assertEquals(
            FIXTURE.readText().normalizeLineEndings(),
            encoded.normalizeLineEndings(),
            "Sıralayıcı değişti. Python tarafını da güncelleyip dosyayı yeniden üret: " +
                "gradle test --tests '*RankingParityTest*' -Dnexi.parity.write=true",
        )
    }

    /**
     * Satır sonu farkı sıralayıcı farkı değil.
     *
     * Depo kökündeki `.gitattributes` checkout'u LF'e sabitliyor, ama o
     * ayardan önce klonlanmış bir çalışma ağacında dosya hâlâ CRLF olabilir.
     * Karşılaştırmayı normalleştirmek, testin yalnızca Windows'ta patlamasını
     * ikinci bir kez engelliyor.
     */
    private fun String.normalizeLineEndings(): String = replace("\r\n", "\n")

    @Test
    fun `the fixture exercises every scoring component`() {
        // Yalnızca tek bileşeni tetikleyen bir senaryo, diğerlerindeki
        // ayrışmayı gizlerdi.
        val candidates = candidates()
        assertTrue(candidates.any { it.media.any { asset -> asset.mimeType.startsWith("video/") } }, "video adayı")
        assertTrue(candidates.any { it.media.isEmpty() }, "metin adayı")
        assertTrue(candidates.any { it.topics.isNotEmpty() }, "konulu aday")
        assertTrue(candidates.any { it.topics.isEmpty() }, "konusuz aday")
        assertTrue(candidates.any { it.likeCount > 0 || it.saveCount > 0 }, "kalite bileşeni")
        assertEquals(2, candidates.map { it.post.ownerId }.distinct().size, "çeşitlendirme için tekrarlı yazar")

        val signals = signals()
        assertTrue(signals.any { it.eventType == RecommendationEventType.CONTENT_HIDDEN }, "olumsuz sinyal")
        assertTrue(signals.any { it.localHour != context.localHour }, "başka zaman diliminden sinyal")
    }

    // ------------------------------------------------------------- senaryo

    private fun candidates(): List<PostDetails> = listOf(
        post(
            id = "aaaaaaaa-0000-4000-8000-000000000001",
            ownerId = AUTHOR_A,
            body = "Kotlin ile yazılım geliştirme üzerine uzun bir yazı",
            ageMinutes = 30,
            likeCount = 12,
            saveCount = 3,
            topics = listOf(topics.topic("teknoloji")),
        ),
        post(
            id = "aaaaaaaa-0000-4000-8000-000000000002",
            ownerId = AUTHOR_A,
            body = "Aynı yazardan ikinci bir teknoloji yazısı",
            ageMinutes = 90,
            likeCount = 0,
            saveCount = 0,
            topics = listOf(topics.topic("teknoloji")),
        ),
        post(
            id = "aaaaaaaa-0000-4000-8000-000000000003",
            ownerId = AUTHOR_B,
            body = "Akşam konsol başında uzun bir oyun seansı",
            ageMinutes = 240,
            likeCount = 5,
            saveCount = 1,
            topics = listOf(topics.topic("oyun")),
            video = true,
        ),
        post(
            id = "aaaaaaaa-0000-4000-8000-000000000004",
            ownerId = AUTHOR_B,
            body = "Sahilde yürüyüş ve kahve",
            ageMinutes = 2_880,
            likeCount = 1,
            saveCount = 0,
            topics = emptyList(),
        ),
    )

    private fun signals(): List<RecommendationSignal> = listOf(
        RecommendationSignal(
            eventType = RecommendationEventType.INTEREST_SELECTED,
            postId = null,
            authorId = null,
            body = null,
            mediaType = null,
            dwellMillis = null,
            completionRatio = null,
            localHour = 21,
            targetFeature = "teknoloji",
            occurredAt = rankedAt.minusSeconds(3_600),
        ),
        RecommendationSignal(
            eventType = RecommendationEventType.CONTENT_VIEW,
            postId = UUID.fromString("bbbbbbbb-0000-4000-8000-000000000001"),
            authorId = AUTHOR_A,
            body = "Önceki teknoloji yazısı",
            topicSlugs = listOf("teknoloji"),
            mediaType = "text",
            dwellMillis = 21_000,
            completionRatio = 0.65,
            localHour = 21,
            targetFeature = null,
            occurredAt = rankedAt.minusSeconds(86_400),
        ),
        RecommendationSignal(
            eventType = RecommendationEventType.CONTENT_LIKED,
            postId = UUID.fromString("bbbbbbbb-0000-4000-8000-000000000002"),
            authorId = AUTHOR_B,
            body = "Video içerik",
            topicSlugs = listOf("oyun"),
            mediaType = "video",
            dwellMillis = null,
            completionRatio = null,
            localHour = 10,
            targetFeature = null,
            occurredAt = rankedAt.minusSeconds(5 * 86_400),
        ),
        RecommendationSignal(
            eventType = RecommendationEventType.CONTENT_HIDDEN,
            postId = UUID.fromString("bbbbbbbb-0000-4000-8000-000000000003"),
            authorId = AUTHOR_B,
            body = "Beğenilmeyen gönderi",
            topicSlugs = listOf("seyahat"),
            mediaType = "text",
            dwellMillis = null,
            completionRatio = null,
            localHour = 21,
            targetFeature = null,
            occurredAt = rankedAt.minusSeconds(2 * 86_400),
        ),
    )

    private fun post(
        id: String,
        ownerId: UUID,
        body: String,
        ageMinutes: Long,
        likeCount: Long,
        saveCount: Long,
        topics: List<Topic>,
        video: Boolean = false,
    ): PostDetails {
        val postId = UUID.fromString(id)
        val createdAt = rankedAt.minusSeconds(ageMinutes * 60)
        return PostDetails(
            post = Post(postId, ownerId, body, PostStatus.PUBLISHED, createdAt, createdAt),
            author = PostAuthorResponse(ownerId.toString(), "Üretici", "uretici"),
            media = if (video) listOf(videoAsset(ownerId, createdAt)) else emptyList(),
            topics = topics,
            likeCount = likeCount,
            saveCount = saveCount,
            commentCount = 0,
            likedByViewer = false,
            savedByViewer = false,
        )
    }

    private fun videoAsset(ownerId: UUID, createdAt: Instant) = MediaAsset(
        id = UUID.fromString("cccccccc-0000-4000-8000-000000000001"),
        ownerId = ownerId,
        storageKey = "parity/video.mp4",
        originalFilename = "video.mp4",
        mimeType = "video/mp4",
        declaredSizeBytes = 1_024,
        actualSizeBytes = 1_024,
        status = MediaStatus.READY,
        createdAt = createdAt,
        updatedAt = createdAt,
    )

    private fun RecommendationSignal.toFixture() = FixtureSignal(
        eventType = eventType.name,
        // Sirali degil, cikarim sirasinda: kayan nokta toplama sirasi degisirse
        // son basamaklar ayrisir ve esitlik testi anlamini yitirir.
        features = signalFeatureKeys(this).toList(),
        localHour = localHour,
        occurredAtEpochSecond = occurredAt.epochSecond,
        dwellMillis = dwellMillis,
        completionRatio = completionRatio,
    )

    private fun PostDetails.toFixture() = FixtureCandidate(
        postId = post.id.toString(),
        ownerId = post.ownerId.toString(),
        createdAtEpochSecond = post.createdAt.epochSecond,
        likeCount = likeCount,
        saveCount = saveCount,
        features = contentFeatureKeys(this).toList(),
    )

    private companion object {
        val SESSION: UUID = UUID.fromString("99999999-0000-4000-8000-000000000001")
        val AUTHOR_A: UUID = UUID.fromString("dddddddd-0000-4000-8000-00000000000a")
        val AUTHOR_B: UUID = UUID.fromString("dddddddd-0000-4000-8000-00000000000b")

        val FIXTURE: Path = Path.of("recommender-lab", "fixtures", "ranking_parity.json")

        val JSON = Json { prettyPrint = true }
    }
}

@Serializable
private data class ParityFixture(
    val modelVersion: String,
    val featureVersion: String,
    val viewerId: String,
    val rankedAtEpochSecond: Long,
    val localHour: Int,
    val signals: List<FixtureSignal>,
    val candidates: List<FixtureCandidate>,
    val expected: List<ExpectedItem>,
    val affinities: Map<String, Double>,
)

@Serializable
private data class FixtureSignal(
    val eventType: String,
    val features: List<String>,
    val localHour: Int,
    val occurredAtEpochSecond: Long,
    val dwellMillis: Long?,
    val completionRatio: Double?,
)

@Serializable
private data class FixtureCandidate(
    val postId: String,
    val ownerId: String,
    val createdAtEpochSecond: Long,
    val likeCount: Long,
    val saveCount: Long,
    val features: List<String>,
)

@Serializable
private data class ExpectedItem(
    val postId: String,
    val position: Int,
    val rawScore: Double,
    val finalScore: Double,
)
