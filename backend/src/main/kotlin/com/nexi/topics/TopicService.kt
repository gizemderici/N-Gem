package com.nexi.topics

import com.nexi.auth.validation
import java.time.Clock
import java.util.UUID

class TopicService(
    private val repository: TopicRepository,
    private val clock: Clock = Clock.systemUTC(),
) {
    /** Onboarding'in ilk adımı: seçilebilir konu kataloğu. Oturum gerektirmez. */
    fun list(): TopicListResponse = TopicListResponse(
        items = repository.listActive().map(Topic::toResponse),
        minSelectable = MIN_TOPICS,
        maxSelectable = MAX_TOPICS,
    )

    fun userTopics(userId: UUID): UserTopicsResponse = response(repository.userTopics(userId))

    /**
     * Kullanıcının sıralamasını baştan yazar. Kısmi güncelleme yoktur: gelen liste
     * neyse kayıtlı sıralama o olur, listedeki sıra doğrudan öncelik sırasıdır.
     */
    fun replace(userId: UUID, request: UpdateUserTopicsRequest): UserTopicsResponse {
        val topicIds = request.topicIds.map { rawId ->
            runCatching { UUID.fromString(rawId) }.getOrElse {
                throw validation("INVALID_TOPIC_ID", "İlgi alanı kimliği geçersiz.", "topicIds")
            }
        }
        if (topicIds.distinct().size != topicIds.size) {
            throw validation("DUPLICATE_TOPIC", "Aynı ilgi alanı birden fazla seçilemez.", "topicIds")
        }
        if (topicIds.size < MIN_TOPICS) {
            throw validation("TOO_FEW_TOPICS", "En az $MIN_TOPICS ilgi alanı seçmelisin.", "topicIds")
        }
        if (topicIds.size > MAX_TOPICS) {
            throw validation("TOO_MANY_TOPICS", "En fazla $MAX_TOPICS ilgi alanı seçebilirsin.", "topicIds")
        }
        if (repository.findActiveByIds(topicIds).size != topicIds.size) {
            throw validation("UNKNOWN_TOPIC", "Seçilen ilgi alanlarından biri artık kullanılamıyor.", "topicIds")
        }

        return response(repository.replaceUserTopics(userId, topicIds, clock.instant()))
    }

    private fun response(userTopics: List<UserTopic>) = UserTopicsResponse(
        items = userTopics.map { it.topic.toResponse() },
        minSelectable = MIN_TOPICS,
        maxSelectable = MAX_TOPICS,
        completed = userTopics.size >= MIN_TOPICS,
        updatedAt = userTopics.maxOfOrNull { it.updatedAt }?.toString(),
    )

    companion object {
        const val MIN_TOPICS = 3
        const val MAX_TOPICS = 10

        /** Sıralamanın ilk kaç konusu "öncelikli" sayılır; akış karışımı bunu kullanır. */
        const val PRIORITY_TOPIC_COUNT = 3
    }
}
