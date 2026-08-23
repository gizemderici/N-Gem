package com.nexi.topics

import java.time.Instant
import java.util.UUID

internal class InMemoryTopicRepository(
    catalog: List<Topic> = defaultCatalog(),
) : TopicRepository {
    private val topicsById = catalog.associateBy { it.id }
    private val selections = mutableMapOf<UUID, List<UserTopic>>()
    private val relations = mutableListOf<TopicRelation>()
    private val inactive = mutableSetOf<UUID>()

    val catalogTopics: List<Topic> = catalog

    fun topic(slug: String): Topic = topicsById.values.first { it.slug == slug }

    fun deactivate(topic: Topic) {
        inactive += topic.id
    }

    /** İlişkiler çift yönlüdür; migration'daki simetrik kayıtla aynı davranış. */
    fun relate(first: Topic, second: Topic, weight: Double = 0.5) {
        relations += TopicRelation(first.id, second.id, weight)
        relations += TopicRelation(second.id, first.id, weight)
    }

    override fun listActive(): List<Topic> =
        topicsById.values.filterNot { it.id in inactive }.sortedBy { it.displayOrder }

    override fun findActiveByIds(ids: List<UUID>): List<Topic> =
        ids.mapNotNull { topicsById[it] }.filterNot { it.id in inactive }

    override fun userTopics(userId: UUID): List<UserTopic> = selections[userId].orEmpty()

    override fun replaceUserTopics(userId: UUID, topicIds: List<UUID>, now: Instant): List<UserTopic> {
        val saved = topicIds.mapIndexed { position, topicId ->
            UserTopic(topicsById.getValue(topicId), position, now)
        }
        selections[userId] = saved
        return saved
    }

    override fun relationsFor(topicIds: List<UUID>): List<TopicRelation> =
        relations.filter { it.topicId in topicIds }

    companion object {
        /**
         * Katalog [TopicCatalog]'dan türetilir; buradaki kimlikler
         * `V5__create_topics.sql` ile aynı desende üretilir. Liste bir zamanlar
         * elle kopyalanmıştı ve gerçek katalogdan sessizce ayrışabiliyordu.
         */
        fun defaultCatalog(): List<Topic> = TopicCatalog.LABELS.entries.mapIndexed { index, (slug, name) ->
            Topic(
                id = UUID.fromString("00000000-0000-4000-8000-%012x".format(index + 1)),
                slug = slug,
                name = name,
                description = null,
                icon = slug,
                colorHex = "#38BDF8",
                displayOrder = index + 1,
            )
        }
    }
}
