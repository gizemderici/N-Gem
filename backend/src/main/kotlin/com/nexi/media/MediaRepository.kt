package com.nexi.media

import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

interface MediaRepository {
    fun create(asset: MediaAsset)
    fun findById(id: UUID): MediaAsset?
    fun markReady(id: UUID, actualSizeBytes: Long, updatedAt: Instant): Boolean

    /** Doğrulama sonrası çözülen video meta verisini ve kapak görselini yazar. */
    fun saveVideoMetadata(
        id: UUID,
        durationSeconds: Double?,
        width: Int?,
        height: Int?,
        thumbnailMediaId: UUID?,
        updatedAt: Instant,
    )

    fun markProcessing(id: UUID, status: MediaProcessingStatus, failureReason: String?, updatedAt: Instant)
    fun markStatus(id: UUID, status: MediaStatus, updatedAt: Instant): Boolean

    /** Belirtilen durumda takılıp kalmış, [updatedBefore] tarihinden eski kayıtlar. */
    fun findStale(status: MediaStatus, updatedBefore: Instant, limit: Int): List<MediaAsset>
}

class JdbcMediaRepository(private val dataSource: DataSource) : MediaRepository {
    override fun create(asset: MediaAsset) {
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """INSERT INTO media_assets
                   (id, owner_id, storage_key, original_filename, mime_type, declared_size_bytes,
                    actual_size_bytes, status, created_at, updated_at)
                   VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"""
            ).use { statement ->
                statement.setObject(1, asset.id)
                statement.setObject(2, asset.ownerId)
                statement.setString(3, asset.storageKey)
                statement.setString(4, asset.originalFilename)
                statement.setString(5, asset.mimeType)
                statement.setLong(6, asset.declaredSizeBytes)
                statement.setObject(7, asset.actualSizeBytes)
                statement.setString(8, asset.status.name)
                statement.setTimestamp(9, Timestamp.from(asset.createdAt))
                statement.setTimestamp(10, Timestamp.from(asset.updatedAt))
                statement.executeUpdate()
            }
        }
    }

    override fun findById(id: UUID): MediaAsset? = dataSource.connection.use { connection ->
        connection.prepareStatement("SELECT * FROM media_assets WHERE id = ?").use { statement ->
            statement.setObject(1, id)
            statement.executeQuery().use { results -> if (results.next()) results.toMediaAsset() else null }
        }
    }

    override fun markReady(id: UUID, actualSizeBytes: Long, updatedAt: Instant): Boolean =
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """UPDATE media_assets SET status = 'READY', actual_size_bytes = ?, updated_at = ?
                   WHERE id = ? AND status = 'PENDING'"""
            ).use { statement ->
                statement.setLong(1, actualSizeBytes)
                statement.setTimestamp(2, Timestamp.from(updatedAt))
                statement.setObject(3, id)
                statement.executeUpdate() == 1
            }
        }

    override fun markStatus(id: UUID, status: MediaStatus, updatedAt: Instant): Boolean =
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                "UPDATE media_assets SET status = ?, updated_at = ? WHERE id = ?"
            ).use { statement ->
                statement.setString(1, status.name)
                statement.setTimestamp(2, Timestamp.from(updatedAt))
                statement.setObject(3, id)
                statement.executeUpdate() == 1
            }
        }

    override fun findStale(status: MediaStatus, updatedBefore: Instant, limit: Int): List<MediaAsset> =
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                "SELECT * FROM media_assets WHERE status = ? AND updated_at < ? ORDER BY updated_at LIMIT ?"
            ).use { statement ->
                statement.setString(1, status.name)
                statement.setTimestamp(2, Timestamp.from(updatedBefore))
                statement.setInt(3, limit)
                statement.executeQuery().use { results ->
                    buildList { while (results.next()) add(results.toMediaAsset()) }
                }
            }
        }

    override fun saveVideoMetadata(
        id: UUID,
        durationSeconds: Double?,
        width: Int?,
        height: Int?,
        thumbnailMediaId: UUID?,
        updatedAt: Instant,
    ) {
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """UPDATE media_assets
                   SET duration_seconds = ?, width = ?, height = ?, thumbnail_media_id = ?, updated_at = ?
                   WHERE id = ?"""
            ).use { statement ->
                if (durationSeconds == null) statement.setNull(1, java.sql.Types.NUMERIC)
                else statement.setDouble(1, durationSeconds)
                if (width == null) statement.setNull(2, java.sql.Types.INTEGER) else statement.setInt(2, width)
                if (height == null) statement.setNull(3, java.sql.Types.INTEGER) else statement.setInt(3, height)
                statement.setObject(4, thumbnailMediaId)
                statement.setTimestamp(5, Timestamp.from(updatedAt))
                statement.setObject(6, id)
                statement.executeUpdate()
            }
        }
    }

    override fun markProcessing(
        id: UUID,
        status: MediaProcessingStatus,
        failureReason: String?,
        updatedAt: Instant,
    ) {
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                "UPDATE media_assets SET processing_status = ?, failure_reason = ?, updated_at = ? WHERE id = ?"
            ).use { statement ->
                statement.setString(1, status.name)
                statement.setString(2, failureReason)
                statement.setTimestamp(3, Timestamp.from(updatedAt))
                statement.setObject(4, id)
                statement.executeUpdate()
            }
        }
    }

    private fun ResultSet.toMediaAsset() = MediaAsset(
        id = getObject("id", UUID::class.java),
        ownerId = getObject("owner_id", UUID::class.java),
        storageKey = getString("storage_key"),
        originalFilename = getString("original_filename"),
        mimeType = getString("mime_type"),
        declaredSizeBytes = getLong("declared_size_bytes"),
        actualSizeBytes = getLong("actual_size_bytes").let { if (wasNull()) null else it },
        status = MediaStatus.valueOf(getString("status")),
        createdAt = getTimestamp("created_at").toInstant(),
        updatedAt = getTimestamp("updated_at").toInstant(),
        processingStatus = MediaProcessingStatus.valueOf(getString("processing_status")),
        failureReason = getString("failure_reason"),
        durationSeconds = getDouble("duration_seconds").let { if (wasNull()) null else it },
        width = getInt("width").let { if (wasNull()) null else it },
        height = getInt("height").let { if (wasNull()) null else it },
        thumbnailMediaId = getObject("thumbnail_media_id", UUID::class.java),
    )
}
