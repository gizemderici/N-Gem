package com.nexi.topics

import java.sql.Connection
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

interface TopicRepository {
    /** Katalogdaki aktif konular, gösterim sırasına göre. */
    fun listActive(): List<Topic>

    /** Verilen kimliklerden aktif olanlar; bulunamayanlar cevapta yer almaz. */
    fun findActiveByIds(ids: List<UUID>): List<Topic>

    /** Kullanıcının kaydettiği sıralama; hiç seçim yapmadıysa boş liste. */
    fun userTopics(userId: UUID): List<UserTopic>

    /** Sıralamayı tamamen değiştirir (kısmi güncelleme yok) ve yeni hâlini döndürür. */
    fun replaceUserTopics(userId: UUID, topicIds: List<UUID>, now: Instant): List<UserTopic>

    /** Verilen konulara komşu olan ilişkili konu bağları. */
    fun relationsFor(topicIds: List<UUID>): List<TopicRelation>
}

class JdbcTopicRepository(private val dataSource: DataSource) : TopicRepository {
    override fun listActive(): List<Topic> = dataSource.connection.use { connection ->
        connection.prepareStatement("$TOPIC_SELECT WHERE t.active ORDER BY t.display_order, t.id").use { statement ->
            statement.executeQuery().use { results -> results.readTopics() }
        }
    }

    override fun findActiveByIds(ids: List<UUID>): List<Topic> {
        if (ids.isEmpty()) return emptyList()
        return dataSource.connection.use { connection ->
            connection.prepareStatement("$TOPIC_SELECT WHERE t.active AND t.id = ANY(?) ORDER BY t.display_order, t.id").use { statement ->
                statement.setArray(1, connection.uuidArray(ids))
                statement.executeQuery().use { results -> results.readTopics() }
            }
        }
    }

    override fun userTopics(userId: UUID): List<UserTopic> = dataSource.connection.use { connection ->
        connection.readUserTopics(userId)
    }

    override fun replaceUserTopics(userId: UUID, topicIds: List<UUID>, now: Instant): List<UserTopic> =
        dataSource.connection.use { connection ->
            connection.autoCommit = false
            try {
                connection.prepareStatement("DELETE FROM user_topics WHERE user_id = ?").use { statement ->
                    statement.setObject(1, userId)
                    statement.executeUpdate()
                }
                connection.prepareStatement(
                    """INSERT INTO user_topics (user_id, topic_id, position, created_at, updated_at)
                       VALUES (?, ?, ?, ?, ?)"""
                ).use { statement ->
                    topicIds.forEachIndexed { position, topicId ->
                        statement.setObject(1, userId)
                        statement.setObject(2, topicId)
                        statement.setInt(3, position)
                        statement.setTimestamp(4, Timestamp.from(now))
                        statement.setTimestamp(5, Timestamp.from(now))
                        statement.addBatch()
                    }
                    if (topicIds.isNotEmpty()) statement.executeBatch()
                }
                val saved = connection.readUserTopics(userId)
                connection.commit()
                saved
            } catch (exception: Throwable) {
                connection.rollback()
                throw exception
            } finally {
                connection.autoCommit = true
            }
        }

    override fun relationsFor(topicIds: List<UUID>): List<TopicRelation> {
        if (topicIds.isEmpty()) return emptyList()
        return dataSource.connection.use { connection ->
            connection.prepareStatement(
                "SELECT topic_id, related_topic_id, weight FROM topic_relations WHERE topic_id = ANY(?)"
            ).use { statement ->
                statement.setArray(1, connection.uuidArray(topicIds))
                statement.executeQuery().use { results ->
                    buildList {
                        while (results.next()) {
                            add(
                                TopicRelation(
                                    topicId = results.getObject("topic_id", UUID::class.java),
                                    relatedTopicId = results.getObject("related_topic_id", UUID::class.java),
                                    weight = results.getBigDecimal("weight").toDouble(),
                                )
                            )
                        }
                    }
                }
            }
        }
    }

    private fun Connection.readUserTopics(userId: UUID): List<UserTopic> = prepareStatement(
        """SELECT t.id, t.slug, t.name, t.description, t.icon, t.color_hex, t.display_order,
                  ut.position, ut.updated_at
           FROM user_topics ut JOIN topics t ON t.id = ut.topic_id
           WHERE ut.user_id = ? ORDER BY ut.position"""
    ).use { statement ->
        statement.setObject(1, userId)
        statement.executeQuery().use { results ->
            buildList {
                while (results.next()) {
                    add(
                        UserTopic(
                            topic = results.toTopic(),
                            position = results.getInt("position"),
                            updatedAt = results.getTimestamp("updated_at").toInstant(),
                        )
                    )
                }
            }
        }
    }

    private fun Connection.uuidArray(ids: List<UUID>) = createArrayOf("uuid", ids.toTypedArray())

    private fun ResultSet.readTopics(): List<Topic> = buildList {
        while (next()) add(toTopic())
    }

    private companion object {
        const val TOPIC_SELECT =
            "SELECT t.id, t.slug, t.name, t.description, t.icon, t.color_hex, t.display_order FROM topics t"
    }
}

internal fun ResultSet.toTopic() = Topic(
    id = getObject("id", UUID::class.java),
    slug = getString("slug"),
    name = getString("name"),
    description = getString("description"),
    icon = getString("icon"),
    colorHex = getString("color_hex").trim(),
    displayOrder = getInt("display_order"),
)
