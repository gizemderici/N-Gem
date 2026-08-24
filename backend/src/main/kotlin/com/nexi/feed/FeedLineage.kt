package com.nexi.feed

import com.nexi.posts.CandidateSource
import kotlinx.serialization.json.Json
import org.postgresql.util.PGobject
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

/**
 * Bir akış isteğinin tam kaydı.
 *
 * Değerlendirilen adayların hepsi yazılır, gösterilmeyenler dâhil: hangi
 * gönderinin neden elendiği ancak elenenler de kayıtlıysa cevaplanabilir.
 */
data class FeedRequestRecord(
    val id: UUID,
    val userId: UUID,
    val sessionId: UUID,
    val requestedAt: Instant,
    val modelVersion: String,
    val policyVersion: String,
    val featureVersion: String,
    val experimentVariant: String?,
    val localHour: Int,
    val timezoneOffsetMinutes: Int,
    val personalized: Boolean,
    /** Siralamanin surdugu sure; model bozulmasi once burada gorunur. */
    val durationMillis: Int? = null,
    /** Golge kosusuysa asil istegin kimligi. */
    val shadowOf: UUID? = null,
    val candidates: List<FeedCandidateRecord>,
    val affinities: Map<String, Double>,
    val signalCount: Int,
) {
    val returnedCount: Int get() = candidates.count { it.position != null }
}

/**
 * Tek bir aday.
 *
 * Etkileşim sayaçları ve yaş burada satır içinde tutuluyor. Eğitim sırasında
 * `posts` tablosundan okunsalardı geleceğin beğenileri geçmiş bir isteğe
 * sızar ve model kendi sonucunu girdi olarak görürdü.
 */
data class FeedCandidateRecord(
    val postId: UUID,
    val source: CandidateSource,
    val rawScore: Double,
    val finalScore: Double,
    /**
     * Bu koşunun ürettiği sıradaki yeri.
     *
     * `finalScore` çeşitlendirme öncesi puan; çok hedefli yeniden sıralama
     * puanı değiştirmeden sırayı değiştirdiği için iki koşuyu karşılaştırmak
     * ancak bu alanla mümkün.
     */
    val rank: Int,
    /** Kullanıcıya gösterildiği slot; gösterilmediyse `null`. */
    val position: Int?,
    val reason: String?,
    val likeCount: Long,
    val commentCount: Long,
    val ageHours: Double,
    val mediaType: String,
    val topicSlugs: List<String>,
)

interface FeedLineageRepository {
    fun record(request: FeedRequestRecord)
}

/** Soy kütüğü deposu olmayan bağlamlar (testler, yerel kurulum) için. */
object NoopFeedLineageRepository : FeedLineageRepository {
    override fun record(request: FeedRequestRecord) = Unit
}

class JdbcFeedLineageRepository(private val dataSource: DataSource) : FeedLineageRepository {
    override fun record(request: FeedRequestRecord) {
        dataSource.connection.use { connection ->
            connection.autoCommit = false
            try {
                connection.prepareStatement(
                    """INSERT INTO feed_requests
                       (id, user_id, session_id, requested_at, model_version, policy_version,
                        feature_version, experiment_variant, local_hour, timezone_offset_minutes,
                        personalized, candidate_count, returned_count, shadow_of, duration_millis)
                       VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"""
                ).use { statement ->
                    statement.setObject(1, request.id)
                    statement.setObject(2, request.userId)
                    statement.setObject(3, request.sessionId)
                    statement.setTimestamp(4, Timestamp.from(request.requestedAt))
                    statement.setString(5, request.modelVersion)
                    statement.setString(6, request.policyVersion)
                    statement.setString(7, request.featureVersion)
                    statement.setString(8, request.experimentVariant)
                    statement.setInt(9, request.localHour)
                    statement.setInt(10, request.timezoneOffsetMinutes)
                    statement.setBoolean(11, request.personalized)
                    statement.setInt(12, request.candidates.size)
                    statement.setInt(13, request.returnedCount)
                    statement.setObject(14, request.shadowOf)
                    if (request.durationMillis == null) {
                        statement.setNull(15, java.sql.Types.INTEGER)
                    } else {
                        statement.setInt(15, request.durationMillis)
                    }
                    statement.executeUpdate()
                }

                connection.prepareStatement(
                    """INSERT INTO feed_candidates
                       (feed_request_id, post_id, candidate_source, raw_score, final_score,
                        position, reason, like_count, comment_count, age_hours, media_type,
                        topic_slugs, rank_position)
                       VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"""
                ).use { statement ->
                    request.candidates.forEach { candidate ->
                        statement.setObject(1, request.id)
                        statement.setObject(2, candidate.postId)
                        statement.setString(3, candidate.source.name)
                        statement.setDouble(4, candidate.rawScore)
                        statement.setDouble(5, candidate.finalScore)
                        if (candidate.position == null) {
                            statement.setNull(6, java.sql.Types.INTEGER)
                        } else {
                            statement.setInt(6, candidate.position)
                        }
                        statement.setString(7, candidate.reason?.take(MAX_REASON_LENGTH))
                        statement.setLong(8, candidate.likeCount)
                        statement.setLong(9, candidate.commentCount)
                        statement.setDouble(10, candidate.ageHours)
                        statement.setString(11, candidate.mediaType)
                        statement.setArray(
                            12,
                            connection.createArrayOf("text", candidate.topicSlugs.toTypedArray()),
                        )
                        statement.setInt(13, candidate.rank)
                        statement.addBatch()
                    }
                    statement.executeBatch()
                }

                connection.prepareStatement(
                    """INSERT INTO user_feature_snapshots
                       (feed_request_id, user_id, feature_version, captured_at, affinities, signal_count)
                       VALUES (?, ?, ?, ?, ?, ?)"""
                ).use { statement ->
                    statement.setObject(1, request.id)
                    statement.setObject(2, request.userId)
                    statement.setString(3, request.featureVersion)
                    statement.setTimestamp(4, Timestamp.from(request.requestedAt))
                    statement.setObject(5, jsonb(request.affinities))
                    statement.setInt(6, request.signalCount)
                    statement.executeUpdate()
                }

                connection.commit()
            } catch (error: Throwable) {
                connection.rollback()
                throw error
            } finally {
                connection.autoCommit = true
            }
        }
    }

    private fun jsonb(value: Map<String, Double>): PGobject = PGobject().apply {
        type = "jsonb"
        this.value = Json.encodeToString(value)
    }

    private companion object {
        /** `feed_candidates.reason` kolonundaki sınır. */
        const val MAX_REASON_LENGTH = 200
    }
}
