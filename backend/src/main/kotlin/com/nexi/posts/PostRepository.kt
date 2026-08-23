package com.nexi.posts

import com.nexi.media.MediaAsset
import com.nexi.moderation.BlockFilter
import com.nexi.media.MediaStatus
import com.nexi.topics.Topic
import com.nexi.topics.toTopic
import java.sql.Connection
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

interface PostRepository {
    fun create(ownerId: UUID, body: String, mediaIds: List<UUID>, topicIds: List<UUID>, now: Instant): Post
    fun findDetails(postId: UUID, viewerId: UUID): PostDetails?
    fun feed(viewerId: UUID, cursor: FeedCursor?, limit: Int): List<PostDetails>

    /**
     * Tek bir kullanıcının gönderileri, kronolojik. Etkileşim durumu [viewerId]
     * için hesaplanır; profil sahibi başkasının profiline baktığında da doğru olsun.
     */
    fun postsByOwner(ownerId: UUID, viewerId: UUID, cursor: FeedCursor?, limit: Int): List<PostDetails>

    /**
     * Tek bir akış katmanının kronolojik sayfası. Medya ve konular yüklenmez;
     * karışım kurulduktan sonra yalnızca sayfaya giren gönderiler için [hydrate] çağrılır.
     */
    fun feedTier(
        viewerId: UUID,
        tier: FeedTier,
        priorityTopicCount: Int,
        cursor: FeedCursor?,
        limit: Int,
    ): List<PostDetails>

    /** Verilen gönderilerin medya ve konu bilgilerini toplu olarak doldurur. */
    fun hydrate(details: List<PostDetails>): List<PostDetails>

    /**
     * Gönderiyi siler ve bağlı görselleri serbest bırakır.
     *
     * @return Depodan silinmesi gereken anahtarlar; gönderi bulunamadıysa `null`.
     *   Boş liste "silindi ama görseli yoktu" demek, bu yüzden `Boolean` yerine
     *   nullable liste dönüyoruz.
     */
    fun markDeleted(postId: UUID, ownerId: UUID, now: Instant): List<String>?
    fun setLike(postId: UUID, userId: UUID, active: Boolean, now: Instant): Long
    fun setSave(postId: UUID, userId: UUID, active: Boolean, now: Instant): Long

    /** Metin araması; alaka sırasına göre. */
    fun search(viewerId: UUID, query: String, cursor: RankedPostCursor?, limit: Int): List<RankedPost>

    /**
     * Keşfet: sorgu yok, popülerlik ve güncellik karışımı.
     *
     * `rankedAt` ilk sayfada sabitlenip sonraki sayfalarda imleçten geri
     * taşınır. Aksi halde her SQL isteğindeki `now()` puanları biraz düşürür
     * ve önceki sayfanın son öğesi yeniden sonuçlara girebilir.
     */
    fun explore(viewerId: UUID, rankedAt: Instant, cursor: RankedPostCursor?, limit: Int): List<RankedPost>
}

/** Sıralama puanıyla birlikte bir gönderi. */
data class RankedPost(val details: PostDetails, val rank: Double)

data class RankedPostCursor(val rank: Double, val createdAt: Instant, val id: UUID)

/** Keşfet güncellik puanının bir haftalık yarılanma süresi. */
internal const val EXPLORE_HALF_LIFE_SECONDS = 604_800.0

class MediaOwnershipException : RuntimeException()
class MediaAlreadyAttachedException : RuntimeException()
class UnknownTopicException : RuntimeException()

class JdbcPostRepository(private val dataSource: DataSource) : PostRepository {

    private companion object {
        /**
         * `detailsSelect` içinde viewerId'nin kaç kez bağlandığı:
         * yorum sayacındaki engel süzgeci (2), beğeni, kayıt ve takip kontrolü.
         */
        const val VIEWER_BINDINGS = 3 + BlockFilter.BINDINGS

    }

    override fun create(ownerId: UUID, body: String, mediaIds: List<UUID>, topicIds: List<UUID>, now: Instant): Post {
        val post = Post(UUID.randomUUID(), ownerId, body, PostStatus.PUBLISHED, now, now)
        dataSource.connection.use { connection ->
            connection.autoCommit = false
            try {
                validateMedia(connection, ownerId, mediaIds)
                validateTopics(connection, topicIds)
                connection.prepareStatement(
                    "INSERT INTO posts (id, owner_id, body, status, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?)"
                ).use { statement ->
                    statement.setObject(1, post.id)
                    statement.setObject(2, post.ownerId)
                    statement.setString(3, post.body)
                    statement.setString(4, post.status.name)
                    statement.setTimestamp(5, Timestamp.from(post.createdAt))
                    statement.setTimestamp(6, Timestamp.from(post.updatedAt))
                    statement.executeUpdate()
                }
                connection.prepareStatement(
                    "INSERT INTO post_media (post_id, media_id, position) VALUES (?, ?, ?)"
                ).use { statement ->
                    mediaIds.forEachIndexed { index, mediaId ->
                        statement.setObject(1, post.id)
                        statement.setObject(2, mediaId)
                        statement.setInt(3, index)
                        statement.addBatch()
                    }
                    if (mediaIds.isNotEmpty()) statement.executeBatch()
                }
                connection.prepareStatement(
                    "INSERT INTO post_topics (post_id, topic_id) VALUES (?, ?)"
                ).use { statement ->
                    topicIds.forEach { topicId ->
                        statement.setObject(1, post.id)
                        statement.setObject(2, topicId)
                        statement.addBatch()
                    }
                    if (topicIds.isNotEmpty()) statement.executeBatch()
                }
                connection.commit()
            } catch (exception: Throwable) {
                connection.rollback()
                throw exception
            } finally {
                connection.autoCommit = true
            }
        }
        return post
    }

    override fun findDetails(postId: UUID, viewerId: UUID): PostDetails? = dataSource.connection.use { connection ->
        val details = connection.prepareStatement(
            "${detailsSelect()} WHERE p.id = ? AND p.status = 'PUBLISHED' AND ${BlockFilter.notBlocked("p.owner_id")}"
        ).use { statement ->
            var index = 1
            repeat(VIEWER_BINDINGS) { statement.setObject(index++, viewerId) }
            statement.setObject(index++, postId)
            repeat(BlockFilter.BINDINGS) { statement.setObject(index++, viewerId) }
            statement.executeQuery().use { results ->
                if (results.next()) results.toDetails() else null
            }
        }
        details?.let { connection.hydrate(listOf(it)).single() }
    }

    override fun feed(viewerId: UUID, cursor: FeedCursor?, limit: Int): List<PostDetails> =
        dataSource.connection.use { connection ->
            val cursorClause = if (cursor == null) "" else "AND (p.created_at, p.id) < (?, ?)"
            connection.prepareStatement(
                "${detailsSelect()} WHERE p.status = 'PUBLISHED' AND ${BlockFilter.notBlocked("p.owner_id")} " +
                    "$cursorClause ORDER BY p.created_at DESC, p.id DESC LIMIT ?"
            ).use { statement ->
                var index = 1
                repeat(VIEWER_BINDINGS) { statement.setObject(index++, viewerId) }
                repeat(BlockFilter.BINDINGS) { statement.setObject(index++, viewerId) }
                if (cursor != null) {
                    statement.setTimestamp(index++, Timestamp.from(cursor.createdAt))
                    statement.setObject(index++, cursor.id)
                }
                statement.setInt(index, limit)
                val details = statement.executeQuery().use { results ->
                    buildList { while (results.next()) add(results.toDetails()) }
                }
                connection.hydrate(details)
            }
        }

    override fun postsByOwner(ownerId: UUID, viewerId: UUID, cursor: FeedCursor?, limit: Int): List<PostDetails> =
        dataSource.connection.use { connection ->
            val cursorClause = if (cursor == null) "" else "AND (p.created_at, p.id) < (?, ?)"
            connection.prepareStatement(
                "${detailsSelect()} WHERE p.owner_id = ? AND p.status = 'PUBLISHED' " +
                    "AND ${BlockFilter.notBlocked("p.owner_id")} $cursorClause " +
                    "ORDER BY p.created_at DESC, p.id DESC LIMIT ?"
            ).use { statement ->
                var index = 1
                repeat(VIEWER_BINDINGS) { statement.setObject(index++, viewerId) }
                statement.setObject(index++, ownerId)
                repeat(BlockFilter.BINDINGS) { statement.setObject(index++, viewerId) }
                if (cursor != null) {
                    statement.setTimestamp(index++, Timestamp.from(cursor.createdAt))
                    statement.setObject(index++, cursor.id)
                }
                statement.setInt(index, limit)
                val details = statement.executeQuery().use { results ->
                    buildList { while (results.next()) add(results.toDetails()) }
                }
                connection.hydrate(details)
            }
        }

    override fun feedTier(
        viewerId: UUID,
        tier: FeedTier,
        priorityTopicCount: Int,
        cursor: FeedCursor?,
        limit: Int,
    ): List<PostDetails> = dataSource.connection.use { connection ->
        val bindings = MutableList<Any>(VIEWER_BINDINGS) { viewerId }
        // Engel süzgeci tier koşulundan önce geliyor; SQL'deki sıra da öyle.
        val blockClause = BlockFilter.notBlocked("p.owner_id")
        repeat(BlockFilter.BINDINGS) { bindings += viewerId }
        val tierClause = tierClause(tier, viewerId, priorityTopicCount, bindings)
        val cursorClause = if (cursor == null) {
            ""
        } else {
            bindings += Timestamp.from(cursor.createdAt)
            bindings += cursor.id
            "AND (p.created_at, p.id) < (?, ?)"
        }
        bindings += limit

        connection.prepareStatement(
            "${detailsSelect()} WHERE p.status = 'PUBLISHED' AND $blockClause AND $tierClause $cursorClause " +
                "ORDER BY p.created_at DESC, p.id DESC LIMIT ?"
        ).use { statement ->
            bindings.forEachIndexed { index, value ->
                when (value) {
                    is Timestamp -> statement.setTimestamp(index + 1, value)
                    is Int -> statement.setInt(index + 1, value)
                    else -> statement.setObject(index + 1, value)
                }
            }
            statement.executeQuery().use { results ->
                buildList { while (results.next()) add(results.toDetails()) }
            }
        }
    }

    override fun hydrate(details: List<PostDetails>): List<PostDetails> {
        if (details.isEmpty()) return details
        return dataSource.connection.use { connection -> connection.hydrate(details) }
    }

    /**
     * Tek işlemde: gönderiyi silinmiş işaretle, görsellerini de silinmiş işaretle
     * ve bağlantı satırlarını kaldır.
     *
     * Bağlantı satırları kalırsa `UNIQUE (media_id)` kısıtı yüzünden o görsel bir
     * daha hiçbir gönderide kullanılamıyor ve depodaki dosya sonsuza kadar kalıyordu.
     */
    override fun markDeleted(postId: UUID, ownerId: UUID, now: Instant): List<String>? =
        dataSource.connection.use { connection ->
            connection.autoCommit = false
            try {
                val deleted = connection.prepareStatement(
                    "UPDATE posts SET status = 'DELETED', updated_at = ? WHERE id = ? AND owner_id = ? AND status = 'PUBLISHED'"
                ).use { statement ->
                    statement.setTimestamp(1, Timestamp.from(now))
                    statement.setObject(2, postId)
                    statement.setObject(3, ownerId)
                    statement.executeUpdate() == 1
                }
                if (!deleted) {
                    connection.rollback()
                    return@use null
                }

                val storageKeys = connection.prepareStatement(
                    """SELECT m.storage_key FROM post_media pm
                       JOIN media_assets m ON m.id = pm.media_id
                       WHERE pm.post_id = ?"""
                ).use { statement ->
                    statement.setObject(1, postId)
                    statement.executeQuery().use { results ->
                        buildList { while (results.next()) add(results.getString("storage_key")) }
                    }
                }

                connection.prepareStatement(
                    """UPDATE media_assets SET status = 'DELETED', updated_at = ?
                       WHERE id IN (SELECT media_id FROM post_media WHERE post_id = ?)"""
                ).use { statement ->
                    statement.setTimestamp(1, Timestamp.from(now))
                    statement.setObject(2, postId)
                    statement.executeUpdate()
                }

                connection.prepareStatement("DELETE FROM post_media WHERE post_id = ?").use { statement ->
                    statement.setObject(1, postId)
                    statement.executeUpdate()
                }

                connection.commit()
                storageKeys
            } catch (exception: Throwable) {
                connection.rollback()
                throw exception
            } finally {
                connection.autoCommit = true
            }
        }

    override fun setLike(postId: UUID, userId: UUID, active: Boolean, now: Instant): Long =
        setInteraction("post_likes", postId, userId, active, now)

    override fun setSave(postId: UUID, userId: UUID, active: Boolean, now: Instant): Long =
        setInteraction("post_saves", postId, userId, active, now)

    private fun setInteraction(table: String, postId: UUID, userId: UUID, active: Boolean, now: Instant): Long =
        dataSource.connection.use { connection ->
            if (active) {
                connection.prepareStatement(
                    "INSERT INTO $table (post_id, user_id, created_at) VALUES (?, ?, ?) ON CONFLICT DO NOTHING"
                ).use { statement ->
                    statement.setObject(1, postId)
                    statement.setObject(2, userId)
                    statement.setTimestamp(3, Timestamp.from(now))
                    statement.executeUpdate()
                }
            } else {
                connection.prepareStatement("DELETE FROM $table WHERE post_id = ? AND user_id = ?").use { statement ->
                    statement.setObject(1, postId)
                    statement.setObject(2, userId)
                    statement.executeUpdate()
                }
            }
            connection.prepareStatement("SELECT COUNT(*) FROM $table WHERE post_id = ?").use { statement ->
                statement.setObject(1, postId)
                statement.executeQuery().use { results -> results.next(); results.getLong(1) }
            }
        }

    private fun validateMedia(connection: Connection, ownerId: UUID, mediaIds: List<UUID>) {
        if (mediaIds.isEmpty()) return
        val placeholders = mediaIds.joinToString(",") { "?" }
        connection.prepareStatement(
            """SELECT m.id, m.owner_id, m.status, m.processing_status, pm.post_id
               FROM media_assets m LEFT JOIN post_media pm ON pm.media_id = m.id
               WHERE m.id IN ($placeholders) FOR UPDATE OF m"""
        ).use { statement ->
            mediaIds.forEachIndexed { index, id -> statement.setObject(index + 1, id) }
            val found = mutableSetOf<UUID>()
            statement.executeQuery().use { results ->
                while (results.next()) {
                    val id = results.getObject("id", UUID::class.java)
                    found += id
                    // İşleme bitmeden gönderiye bağlanamaz; bugün video doğrudan
                    // READY oluyor ama kuyruk eklendiğinde bu kontrol devreye girecek.
                    if (results.getObject("owner_id", UUID::class.java) != ownerId ||
                        results.getString("status") != "READY" ||
                        results.getString("processing_status") != "READY"
                    ) {
                        throw MediaOwnershipException()
                    }
                    if (results.getObject("post_id") != null) throw MediaAlreadyAttachedException()
                }
            }
            if (found.size != mediaIds.size) throw MediaOwnershipException()
        }
    }

    private fun validateTopics(connection: Connection, topicIds: List<UUID>) {
        if (topicIds.isEmpty()) return
        connection.prepareStatement("SELECT COUNT(*) FROM topics WHERE active AND id = ANY(?)").use { statement ->
            statement.setArray(1, connection.createArrayOf("uuid", topicIds.toTypedArray()))
            statement.executeQuery().use { results ->
                results.next()
                if (results.getInt(1) != topicIds.size) throw UnknownTopicException()
            }
        }
    }

    /**
     * Katman koşulları dışlayıcıdır; alt katmanlar üst katmanlara düşen gönderileri
     * `NOT EXISTS` ile eler, böylece bir gönderi akışta iki kez görünmez.
     */
    private fun tierClause(
        tier: FeedTier,
        viewerId: UUID,
        priorityTopicCount: Int,
        bindings: MutableList<Any>,
    ): String {
        fun selected(comparison: String): String {
            bindings += viewerId
            bindings += priorityTopicCount
            return """EXISTS (SELECT 1 FROM post_topics pt JOIN user_topics ut ON ut.topic_id = pt.topic_id
                              WHERE pt.post_id = p.id AND ut.user_id = ? AND ut.position $comparison ?)"""
        }

        fun anySelected(): String {
            bindings += viewerId
            return """EXISTS (SELECT 1 FROM post_topics pt JOIN user_topics ut ON ut.topic_id = pt.topic_id
                              WHERE pt.post_id = p.id AND ut.user_id = ?)"""
        }

        fun anyRelated(): String {
            bindings += viewerId
            return """EXISTS (SELECT 1 FROM post_topics pt
                              JOIN topic_relations tr ON tr.related_topic_id = pt.topic_id
                              JOIN user_topics ut ON ut.topic_id = tr.topic_id AND ut.user_id = ?
                              WHERE pt.post_id = p.id)"""
        }

        fun followed(): String {
            bindings += viewerId
            return "EXISTS (SELECT 1 FROM follows f WHERE f.follower_id = ? AND f.followee_id = p.owner_id)"
        }

        // Takip en üstte: alt katmanların hepsi "takip edilmiyor" koşulunu ekler,
        // yoksa takip ettiğin birinin gönderisi hem burada hem konu katmanında çıkardı.
        return when (tier) {
            FeedTier.FOLLOWING -> followed()
            FeedTier.PRIORITY_TOPIC -> "NOT ${followed()} AND ${selected("<")}"
            FeedTier.OTHER_TOPIC -> "NOT ${followed()} AND ${selected(">=")} AND NOT ${selected("<")}"
            FeedTier.RELATED_TOPIC -> "NOT ${followed()} AND NOT ${anySelected()} AND ${anyRelated()}"
            FeedTier.DISCOVERY -> "NOT ${followed()} AND NOT ${anySelected()} AND NOT ${anyRelated()}"
        }
    }

    /**
     * Alaka sıralı sorgular ortak bir iskelet kullanıyor: iç sorgu puanı
     * hesaplıyor, dış sorgu imleci uyguluyor. Puanı `WHERE` içinde tekrar
     * hesaplamak yerine sarmalamak hem okunur hem de ifadeyi bir kez yazdırıyor.
     */
    private fun rankedQuery(rankExpression: String, filter: String, cursor: RankedPostCursor?): String {
        val cursorClause = if (cursor == null) "" else "WHERE (rank, created_at, id) < (?, ?, ?)"
        return """SELECT * FROM (
                    SELECT ${detailsColumns()}, $rankExpression AS rank
                    ${detailsFrom()}
                    WHERE $filter
                  ) ranked
                  $cursorClause
                  ORDER BY rank DESC, created_at DESC, id DESC LIMIT ?"""
    }

    override fun search(viewerId: UUID, query: String, cursor: RankedPostCursor?, limit: Int): List<RankedPost> =
        dataSource.connection.use { connection ->
            val sql = rankedQuery(
                rankExpression = "ts_rank(p.search_vector, websearch_to_tsquery('turkish_simple', ?))",
                filter = "p.status = 'PUBLISHED' " +
                    "AND p.search_vector @@ websearch_to_tsquery('turkish_simple', ?) " +
                    "AND ${BlockFilter.notBlocked("p.owner_id")}",
                cursor = cursor,
            )
            connection.prepareStatement(sql).use { statement ->
                var index = 1
                repeat(VIEWER_BINDINGS) { statement.setObject(index++, viewerId) }
                statement.setString(index++, query)   // ts_rank
                statement.setString(index++, query)   // @@ eşleşmesi
                repeat(BlockFilter.BINDINGS) { statement.setObject(index++, viewerId) }
                if (cursor != null) {
                    statement.setDouble(index++, cursor.rank)
                    statement.setTimestamp(index++, Timestamp.from(cursor.createdAt))
                    statement.setObject(index++, cursor.id)
                }
                statement.setInt(index, limit)
                statement.executeQuery().use { results ->
                    buildList { while (results.next()) add(RankedPost(results.toDetails(), results.getDouble("rank"))) }
                }
            }
        }

    override fun explore(viewerId: UUID, rankedAt: Instant, cursor: RankedPostCursor?, limit: Int): List<RankedPost> =
        dataSource.connection.use { connection ->
            // Etkileşim logaritmik ağırlıklı: bir gönderinin 1000 yerine 2000
            // beğeni alması sırayı iki katına çıkarmasın. Güncellik üstel
            // sönümlemeyle giriyor; bir haftalık içerik puanının yarısını yitiriyor.
            val sql = rankedQuery(
                rankExpression = """
                    ln(1 + (SELECT COUNT(*) FROM post_likes pl WHERE pl.post_id = p.id)
                          + (SELECT COUNT(*) FROM comments c WHERE c.post_id = p.id AND c.status = 'PUBLISHED'))
                    * exp(-EXTRACT(EPOCH FROM (? - p.created_at)) / $EXPLORE_HALF_LIFE_SECONDS)
                """.trimIndent(),
                filter = "p.status = 'PUBLISHED' AND p.created_at <= ? " +
                    "AND ${BlockFilter.notBlocked("p.owner_id")}",
                cursor = cursor,
            )
            connection.prepareStatement(sql).use { statement ->
                var index = 1
                repeat(VIEWER_BINDINGS) { statement.setObject(index++, viewerId) }
                statement.setTimestamp(index++, Timestamp.from(rankedAt)) // puan referansı
                statement.setTimestamp(index++, Timestamp.from(rankedAt)) // aday kümesi
                repeat(BlockFilter.BINDINGS) { statement.setObject(index++, viewerId) }
                if (cursor != null) {
                    statement.setDouble(index++, cursor.rank)
                    statement.setTimestamp(index++, Timestamp.from(cursor.createdAt))
                    statement.setObject(index++, cursor.id)
                }
                statement.setInt(index, limit)
                statement.executeQuery().use { results ->
                    buildList { while (results.next()) add(RankedPost(results.toDetails(), results.getDouble("rank"))) }
                }
            }
        }

    private fun detailsSelect() = "SELECT ${detailsColumns()} ${detailsFrom()}"

    /**
     * Sütunlar ve `FROM` ayrı duruyor ki arama sorguları araya bir sıralama
     * sütunu (`ts_rank`) ekleyebilsin.
     */
    private fun detailsColumns() =
        """p.id, p.owner_id, p.body, p.status, p.created_at, p.updated_at,
                  u.full_name, u.username,
                  (SELECT COUNT(*) FROM post_likes pl WHERE pl.post_id = p.id) AS like_count,
                  (SELECT COUNT(*) FROM post_saves ps WHERE ps.post_id = p.id) AS save_count,
                  (SELECT COUNT(*) FROM comments c
                    WHERE c.post_id = p.id AND c.status = 'PUBLISHED'
                      AND ${BlockFilter.notBlocked("c.author_id")}) AS comment_count,
                  EXISTS(SELECT 1 FROM post_likes pl WHERE pl.post_id = p.id AND pl.user_id = ?) AS liked_by_viewer,
                  EXISTS(SELECT 1 FROM post_saves ps WHERE ps.post_id = p.id AND ps.user_id = ?) AS saved_by_viewer,
                  EXISTS(SELECT 1 FROM follows f WHERE f.follower_id = ? AND f.followee_id = p.owner_id) AS author_followed,
                  am.storage_key AS author_avatar_key"""

    private fun detailsFrom() =
        """FROM posts p
           JOIN users u ON u.id = p.owner_id
           LEFT JOIN media_assets am ON am.id = u.avatar_media_id AND am.status = 'READY'"""

    private fun ResultSet.toDetails(): PostDetails {
        val post = Post(
            id = getObject("id", UUID::class.java),
            ownerId = getObject("owner_id", UUID::class.java),
            body = getString("body"),
            status = PostStatus.valueOf(getString("status")),
            createdAt = getTimestamp("created_at").toInstant(),
            updatedAt = getTimestamp("updated_at").toInstant(),
        )
        return PostDetails(
            post = post,
            author = PostAuthorResponse(
                id = post.ownerId.toString(),
                fullName = getString("full_name"),
                username = getString("username"),
                followedByMe = getBoolean("author_followed"),
                avatarStorageKey = getString("author_avatar_key"),
            ),
            media = emptyList(),
            topics = emptyList(),
            likeCount = getLong("like_count"),
            saveCount = getLong("save_count"),
            commentCount = getLong("comment_count"),
            likedByViewer = getBoolean("liked_by_viewer"),
            savedByViewer = getBoolean("saved_by_viewer"),
        )
    }

    private fun Connection.hydrate(details: List<PostDetails>): List<PostDetails> {
        if (details.isEmpty()) return details
        val postIds = details.map { it.post.id }
        val media = loadMedia(postIds)
        val topics = loadTopics(postIds)
        return details.map { item ->
            item.copy(
                media = media[item.post.id].orEmpty(),
                topics = topics[item.post.id].orEmpty(),
            )
        }
    }

    private fun Connection.loadMedia(postIds: List<UUID>): Map<UUID, List<MediaAsset>> = prepareStatement(
        """SELECT pm.post_id, m.* FROM post_media pm JOIN media_assets m ON m.id = pm.media_id
           WHERE pm.post_id = ANY(?) ORDER BY pm.post_id, pm.position"""
    ).use { statement ->
        statement.setArray(1, createArrayOf("uuid", postIds.toTypedArray()))
        statement.executeQuery().use { results ->
            buildMap<UUID, MutableList<MediaAsset>> {
                while (results.next()) {
                    val postId = results.getObject("post_id", UUID::class.java)
                    getOrPut(postId) { mutableListOf() }.add(
                        MediaAsset(
                            id = results.getObject("id", UUID::class.java),
                            ownerId = results.getObject("owner_id", UUID::class.java),
                            storageKey = results.getString("storage_key"),
                            originalFilename = results.getString("original_filename"),
                            mimeType = results.getString("mime_type"),
                            declaredSizeBytes = results.getLong("declared_size_bytes"),
                            actualSizeBytes = results.getLong("actual_size_bytes").let { if (results.wasNull()) null else it },
                            status = MediaStatus.valueOf(results.getString("status")),
                            createdAt = results.getTimestamp("created_at").toInstant(),
                            updatedAt = results.getTimestamp("updated_at").toInstant(),
                        )
                    )
                }
            }
        }
    }

    private fun Connection.loadTopics(postIds: List<UUID>): Map<UUID, List<Topic>> = prepareStatement(
        """SELECT pt.post_id, t.id, t.slug, t.name, t.description, t.icon, t.color_hex, t.display_order
           FROM post_topics pt JOIN topics t ON t.id = pt.topic_id
           WHERE pt.post_id = ANY(?) ORDER BY pt.post_id, t.display_order"""
    ).use { statement ->
        statement.setArray(1, createArrayOf("uuid", postIds.toTypedArray()))
        statement.executeQuery().use { results ->
            buildMap<UUID, MutableList<Topic>> {
                while (results.next()) {
                    val postId = results.getObject("post_id", UUID::class.java)
                    getOrPut(postId) { mutableListOf() }.add(results.toTopic())
                }
            }
        }
    }
}
