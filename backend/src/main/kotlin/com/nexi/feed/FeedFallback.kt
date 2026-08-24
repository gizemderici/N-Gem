package com.nexi.feed

import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

/**
 * Kişiselleştirmenin çalışmadığı istekler.
 *
 * Yedeğe düşmek sessiz bir olay: kullanıcı kronolojik akış görüyor ve hiçbir
 * şey bozulmuş gibi durmuyor. Kayıt tutulmazsa bozuk bir modelin fark edilmesi
 * kullanıcı şikâyetine kalır. Oran ani yükseldiğinde alarm kaynağı bu tablo.
 */
enum class FallbackReason {
    /** Sıralama sırasında beklenmedik hata; asıl alarm sebebi. */
    RANKING_ERROR,

    /** Kullanıcı kişiselleştirmeye rıza vermemiş. */
    CONSENT_MISSING,

    /** Öldürme anahtarı kapalı ya da kullanıcı kontrol kolunda. */
    EXPERIMENT_DISABLED,

    /** Aday havuzu boş; içerik yok ya da hepsi elendi. */
    NO_CANDIDATES,

    /**
     * Kolun hedef yapılandırması sıralamayı bozdu; varsayılan ağırlıklara
     * dönüldü. Kronolojiğe düşmekten farklı: aday havuzu ve profil sağlam.
     */
    OBJECTIVES_ERROR,
}

data class FeedFallback(
    val userId: UUID,
    val occurredAt: Instant,
    val reason: FallbackReason,
    val modelVersion: String?,
    val experimentVariant: String?,
    val detail: String?,
)

fun interface FeedFallbackRecorder {
    fun record(fallback: FeedFallback)

    companion object {
        val NOOP = FeedFallbackRecorder { }
    }
}

class JdbcFeedFallbackRecorder(private val dataSource: DataSource) : FeedFallbackRecorder {
    override fun record(fallback: FeedFallback) {
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """INSERT INTO feed_fallbacks
                   (id, user_id, occurred_at, reason, model_version, experiment_variant, detail)
                   VALUES (?, ?, ?, ?, ?, ?, ?)"""
            ).use { statement ->
                statement.setObject(1, UUID.randomUUID())
                statement.setObject(2, fallback.userId)
                statement.setTimestamp(3, Timestamp.from(fallback.occurredAt))
                statement.setString(4, fallback.reason.name)
                statement.setString(5, fallback.modelVersion)
                statement.setString(6, fallback.experimentVariant)
                // Yığın izi değil, sınıf adı ve kısa mesaj: tam iz kişisel veri
                // taşıyabilir ve kolonu şişirir.
                statement.setString(7, fallback.detail?.take(MAX_DETAIL_LENGTH))
                statement.executeUpdate()
            }
        }
    }

    private companion object {
        const val MAX_DETAIL_LENGTH = 300
    }
}
