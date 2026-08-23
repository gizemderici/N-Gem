package com.nexi.topics

import kotlinx.serialization.Serializable
import java.time.Instant
import java.util.UUID

/** Uygulamanın sunduğu ilgi alanı kataloğundaki tek bir konu. */
data class Topic(
    val id: UUID,
    val slug: String,
    val name: String,
    val description: String?,
    val icon: String,
    val colorHex: String,
    val displayOrder: Int,
)

/** Kullanıcının onboarding'de belirlediği sıralamadaki tek bir satır. `position` 0'dan başlar. */
data class UserTopic(
    val topic: Topic,
    val position: Int,
    val updatedAt: Instant,
)

/** İki konu arasındaki "ilişkili kategori" bağı; %20'lik ilişkili katman bunu kullanır. */
data class TopicRelation(
    val topicId: UUID,
    val relatedTopicId: UUID,
    val weight: Double,
)

@Serializable
data class TopicResponse(
    val id: String,
    val slug: String,
    val name: String,
    val description: String? = null,
    val icon: String,
    val colorHex: String,
)

/** Gönderi ve akış cevaplarında konuyu tanıtmaya yeten kısa gösterim. */
@Serializable
data class TopicSummaryResponse(
    val id: String,
    val slug: String,
    val name: String,
)

@Serializable
data class TopicListResponse(
    val items: List<TopicResponse>,
    val minSelectable: Int,
    val maxSelectable: Int,
)

@Serializable
data class UpdateUserTopicsRequest(
    val topicIds: List<String> = emptyList(),
)

@Serializable
data class UserTopicsResponse(
    val items: List<TopicResponse>,
    val minSelectable: Int,
    val maxSelectable: Int,
    val completed: Boolean,
    val updatedAt: String? = null,
)

fun Topic.toResponse() = TopicResponse(
    id = id.toString(),
    slug = slug,
    name = name,
    description = description,
    icon = icon,
    colorHex = colorHex,
)

fun Topic.toSummary() = TopicSummaryResponse(id = id.toString(), slug = slug, name = name)
