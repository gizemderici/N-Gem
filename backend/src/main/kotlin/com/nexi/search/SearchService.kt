package com.nexi.search

import com.nexi.auth.ApiException
import com.nexi.auth.validation
import com.nexi.media.AvatarUrls
import com.nexi.media.ObjectStorage
import com.nexi.posts.PostResponse
import com.nexi.posts.RankedPost
import com.nexi.posts.RankedPostCursor
import com.nexi.posts.PostRepository
import com.nexi.posts.toResponse
import com.nexi.topics.toResponse
import io.ktor.http.HttpStatusCode
import java.nio.charset.StandardCharsets
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.UUID

class SearchService(
    private val posts: PostRepository,
    private val repository: SearchRepository,
    private val storage: ObjectStorage,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val mediaUrlExpiry: Duration = Duration.ofMinutes(15)

    /** Birleşik arama: her bölümün ilk birkaç sonucu, tek istekte. */
    fun searchAll(viewerId: UUID, rawQuery: String?): SearchResponse {
        val query = normalize(rawQuery)
        return SearchResponse(
            query = query,
            users = repository.users(viewerId, query, null, COMBINED_SECTION_SIZE).map { it.toResponse() },
            posts = posts.search(viewerId, query, null, COMBINED_SECTION_SIZE).map { it.toResponse() },
            topics = repository.topics(query, COMBINED_SECTION_SIZE).map { it.toResponse() },
        )
    }

    fun searchUsers(viewerId: UUID, rawQuery: String?, rawCursor: String?, requestedLimit: Int?): SearchUserPageResponse {
        val query = normalize(rawQuery)
        val limit = pageSize(requestedLimit)
        val rows = repository.users(viewerId, query, rawCursor?.let { decodeCursor(it) }, limit + 1)
        val hasMore = rows.size > limit
        val items = rows.take(limit)

        return SearchUserPageResponse(
            items = items.map { it.toResponse() },
            nextCursor = if (hasMore) items.lastOrNull()?.let { encodeCursor(it.rank, it.createdAt, it.id) } else null,
        )
    }

    fun searchPosts(viewerId: UUID, rawQuery: String?, rawCursor: String?, requestedLimit: Int?): SearchPostPageResponse {
        val query = normalize(rawQuery)
        val limit = pageSize(requestedLimit)
        val rows = posts.search(viewerId, query, rawCursor?.let { decodePostCursor(it) }, limit + 1)
        val hasMore = rows.size > limit
        val items = rows.take(limit)

        return SearchPostPageResponse(
            items = items.map { it.toResponse() },
            nextCursor = if (hasMore) items.lastOrNull()?.let { it.cursorString() } else null,
        )
    }

    /** Keşfet sorgu almaz; popülerlik ve güncellik karışımıyla sıralanır. */
    fun explore(viewerId: UUID, rawCursor: String?, requestedLimit: Int?): ExploreResponse {
        val limit = pageSize(requestedLimit)
        val cursor = rawCursor?.let { decodeExploreCursor(it) }
        val rankedAt = cursor?.rankedAt ?: clock.instant()
        val rows = posts.explore(viewerId, rankedAt, cursor?.ranked?.toPostCursor(), limit + 1)
        val hasMore = rows.size > limit
        val items = rows.take(limit)

        return ExploreResponse(
            items = items.map { it.toResponse() },
            nextCursor = if (hasMore) items.lastOrNull()?.let { it.exploreCursorString(rankedAt) } else null,
        )
    }

    // ----------------------------------------------------------- yardımcılar

    /**
     * Boş sorgu bütün veritabanını taramak demek; aşırı uzun sorgu ise
     * ayrıştırıcıyı boşuna yorar. İkisi de reddediliyor.
     */
    private fun normalize(rawQuery: String?): String {
        val query = rawQuery?.trim().orEmpty()
        if (query.isEmpty()) throw validation("EMPTY_QUERY", "Arama terimi gerekli.", "q")
        if (query.length > MAX_QUERY_LENGTH) {
            throw validation("QUERY_TOO_LONG", "Arama terimi en fazla $MAX_QUERY_LENGTH karakter olabilir.", "q")
        }
        return query
    }

    private fun pageSize(requested: Int?) = (requested ?: DEFAULT_PAGE_SIZE).coerceIn(1, MAX_PAGE_SIZE)

    private fun SearchUser.toResponse(): SearchUserResponse {
        val avatar = AvatarUrls.of(storage, avatarStorageKey)
        return SearchUserResponse(
            id = id.toString(),
            fullName = fullName,
            username = username,
            avatarUrl = avatar?.url,
            avatarUrlExpiresInSeconds = avatar?.expiresInSeconds,
            followerCount = followerCount,
            followedByMe = followedByViewer,
            isMe = isViewer,
        )
    }

    private fun RankedPost.toResponse(): PostResponse = details.toResponse(storage, mediaUrlExpiry)

    private fun RankedPost.cursorString(): String =
        encodeCursor(rank, details.post.createdAt, details.post.id)

    private fun RankedPost.exploreCursorString(rankedAt: Instant): String =
        encodeExploreCursor(rankedAt, rank, details.post.createdAt, details.post.id)

    companion object {
        const val MAX_QUERY_LENGTH = 100
        const val DEFAULT_PAGE_SIZE = 20
        const val MAX_PAGE_SIZE = 50
        const val COMBINED_SECTION_SIZE = 5

        /**
         * Puan imleçte metin olarak taşınıyor ve **tam** yazılmak zorunda.
         *
         * Sabit ondalıkla (`%.9f`) yuvarlamak sessiz bir hataya yol açıyordu:
         * yuvarlama yukarı gittiğinde imleçteki puan gerçek puandan büyük
         * kalıyor, `rank < cursor.rank` koşulu o öğeyi tekrar geçiriyor ve
         * aynı gönderi bir sonraki sayfada ikinci kez çıkıyordu.
         * `Double.toString` en kısa tam-dönüşlü gösterimi verir.
         */
        internal fun encodeCursor(rank: Double, createdAt: Instant, id: UUID): String {
            val raw = "$rank|$createdAt|$id"
            return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.toByteArray(StandardCharsets.UTF_8))
        }

        internal fun decodeCursor(rawCursor: String): RankedCursor = try {
            val parts = String(Base64.getUrlDecoder().decode(rawCursor), StandardCharsets.UTF_8).split('|', limit = 3)
            RankedCursor(parts[0].toDouble(), Instant.parse(parts[1]), UUID.fromString(parts[2]))
        } catch (_: Throwable) {
            throw ApiException(HttpStatusCode.BadRequest, "INVALID_CURSOR", "Liste imleci geçersiz.", "cursor")
        }

        internal fun decodePostCursor(rawCursor: String): RankedPostCursor =
            decodeCursor(rawCursor).toPostCursor()

        internal fun encodeExploreCursor(rankedAt: Instant, rank: Double, createdAt: Instant, id: UUID): String {
            val raw = "$rankedAt|$rank|$createdAt|$id"
            return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.toByteArray(StandardCharsets.UTF_8))
        }

        internal fun decodeExploreCursor(rawCursor: String): ExploreCursor = try {
            val parts = String(Base64.getUrlDecoder().decode(rawCursor), StandardCharsets.UTF_8).split('|', limit = 4)
            ExploreCursor(
                rankedAt = Instant.parse(parts[0]),
                ranked = RankedCursor(parts[1].toDouble(), Instant.parse(parts[2]), UUID.fromString(parts[3])),
            )
        } catch (_: Throwable) {
            throw ApiException(HttpStatusCode.BadRequest, "INVALID_CURSOR", "Liste imleci geçersiz.", "cursor")
        }

    }
}

private fun RankedCursor.toPostCursor() = RankedPostCursor(rank, createdAt, id)
