package com.nexi.search

import com.nexi.posts.PostResponse
import com.nexi.topics.TopicResponse
import kotlinx.serialization.Serializable
import java.time.Instant
import java.util.UUID

/**
 * Alaka sıralı listelerin imleci.
 *
 * Sıralama `(rank, createdAt, id)` üçlüsüne göre olduğu için imleç de üçünü
 * birden taşımak zorunda; yalnızca zaman damgası taşısaydı alaka sırası
 * sayfa sınırında bozulurdu. Puan ve zaman damgası imleçte kayıpsız metin
 * olarak tutulur; yuvarlama sayfalar arasında tekrar ya da atlama üretirdi.
 */
data class RankedCursor(val rank: Double, val createdAt: Instant, val id: UUID)

/** Keşfet puanının hesaplandığı sabit anı da taşıyan imleç. */
data class ExploreCursor(val rankedAt: Instant, val ranked: RankedCursor)

data class SearchUser(
    val id: UUID,
    val fullName: String,
    val username: String,
    val avatarStorageKey: String?,
    val followerCount: Long,
    val followedByViewer: Boolean,
    val isViewer: Boolean,
    val rank: Double,
    val createdAt: Instant,
)

@Serializable
data class SearchUserResponse(
    val id: String,
    val fullName: String,
    val username: String,
    val avatarUrl: String? = null,
    val avatarUrlExpiresInSeconds: Long? = null,
    val followerCount: Long,
    val followedByMe: Boolean,
    val isMe: Boolean,
)

@Serializable
data class SearchUserPageResponse(
    val items: List<SearchUserResponse>,
    val nextCursor: String? = null,
)

@Serializable
data class SearchPostPageResponse(
    val items: List<PostResponse>,
    val nextCursor: String? = null,
)

/** Birleşik arama: her bölümün ilk birkaç sonucu. */
@Serializable
data class SearchResponse(
    val query: String,
    val users: List<SearchUserResponse>,
    val posts: List<PostResponse>,
    val topics: List<TopicResponse>,
)

@Serializable
data class ExploreResponse(
    val items: List<PostResponse>,
    val nextCursor: String? = null,
)
