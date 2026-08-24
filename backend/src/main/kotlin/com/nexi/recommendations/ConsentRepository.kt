package com.nexi.recommendations

import kotlinx.serialization.Serializable
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

/**
 * Kişiselleştirme rızası.
 *
 * Rıza yoksa davranış olayı toplanmaz ve akış kişiselleştirilmez. Kayıt
 * tutulmadan "kullanıcı ne zaman, sözleşmenin hangi sürümüne onay verdi"
 * sorusunun cevabı yok; Faz 7'deki dışa aktarma ve silme talepleri de bu
 * kaydın üzerine gelecek.
 *
 * Varsayılan **rıza yok**: kayıt bulunmayan kullanıcı için kişiselleştirme
 * kapalı sayılır. Sessiz varsayılanı "açık" yapmak, kullanıcının hiç
 * sorulmadan profillenmesi demek olurdu.
 */
data class RecommendationConsent(
    val granted: Boolean,
    val contractVersion: Int,
    val updatedAt: Instant?,
) {
    companion object {
        val NONE = RecommendationConsent(granted = false, contractVersion = 0, updatedAt = null)
    }
}

@Serializable
data class ConsentResponse(
    val granted: Boolean,
    val contractVersion: Int,
    val currentContractVersion: Int,
    val updatedAt: String?,
)

@Serializable
data class UpdateConsentRequest(val granted: Boolean)

interface ConsentRepository {
    fun find(userId: UUID): RecommendationConsent
    fun set(userId: UUID, granted: Boolean, contractVersion: Int, now: Instant): RecommendationConsent
}

/**
 * Rıza deposu bağlanmamış bağlamlarda kişiselleştirme açık sayılır.
 *
 * Bu yalnızca testler ve öneri deposu olmayan yerel kurulum içindir; orada
 * zaten toplanan veri yok. Üretimde her zaman [JdbcConsentRepository] bağlı.
 */
object AlwaysGrantedConsentRepository : ConsentRepository {
    override fun find(userId: UUID) = RecommendationConsent(true, ConsentContract.VERSION, null)
    override fun set(userId: UUID, granted: Boolean, contractVersion: Int, now: Instant) = find(userId)
}

object ConsentContract {
    /** Kullanıcıya gösterilen kişiselleştirme metninin sürümü. */
    const val VERSION = 1
}

class JdbcConsentRepository(private val dataSource: DataSource) : ConsentRepository {
    override fun find(userId: UUID): RecommendationConsent = dataSource.connection.use { connection ->
        connection.prepareStatement(
            "SELECT granted, contract_version, updated_at FROM recommendation_consents WHERE user_id = ?"
        ).use { statement ->
            statement.setObject(1, userId)
            statement.executeQuery().use { results ->
                if (!results.next()) return@use RecommendationConsent.NONE
                RecommendationConsent(
                    granted = results.getBoolean("granted"),
                    contractVersion = results.getInt("contract_version"),
                    updatedAt = results.getTimestamp("updated_at").toInstant(),
                )
            }
        }
    }

    override fun set(userId: UUID, granted: Boolean, contractVersion: Int, now: Instant): RecommendationConsent {
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """INSERT INTO recommendation_consents (user_id, granted, contract_version, updated_at)
                   VALUES (?, ?, ?, ?)
                   ON CONFLICT (user_id) DO UPDATE
                   SET granted = EXCLUDED.granted,
                       contract_version = EXCLUDED.contract_version,
                       updated_at = EXCLUDED.updated_at"""
            ).use { statement ->
                statement.setObject(1, userId)
                statement.setBoolean(2, granted)
                statement.setInt(3, contractVersion)
                statement.setTimestamp(4, Timestamp.from(now))
                statement.executeUpdate()
            }
        }
        return RecommendationConsent(granted, contractVersion, now)
    }
}
