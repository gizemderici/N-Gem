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
    fun markStatus(id: UUID, status: MediaStatus, updatedAt: Instant): Boolean
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
    )
}
