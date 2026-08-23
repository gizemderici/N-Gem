package com.nexi.messaging

import com.nexi.media.MediaAsset
import com.nexi.posts.PostAuthorResponse
import kotlinx.serialization.Serializable
import java.time.Instant
import java.util.UUID

enum class MessageType { TEXT, IMAGE, VIDEO, SYSTEM }

enum class MessageStatus { SENT, DELETED }

data class Message(
    val id: UUID,
    val conversationId: UUID,
    val senderId: UUID,
    val type: MessageType,
    val body: String?,
    val mediaId: UUID?,
    val status: MessageStatus,
    val createdAt: Instant,
)

data class MessageDetails(
    val message: Message,
    val sender: PostAuthorResponse,
    val media: MediaAsset?,
    /** Karşı tarafın okundu su seviyesi bu mesajı geçtiyse görülmüş sayılır. */
    val seenByOther: Boolean,
)

data class ConversationSummary(
    val id: UUID,
    val other: PostAuthorResponse,
    val lastMessage: MessageDetails?,
    val unreadCount: Long,
    val lastMessageAt: Instant?,
)

/** Konuşma ve mesaj listelerinin imleci. */
data class MessageCursor(val createdAt: Instant, val id: UUID)

@Serializable
data class CreateConversationRequest(val username: String)

@Serializable
data class SendMessageRequest(
    val text: String? = null,
    val mediaId: String? = null,
)

@Serializable
data class MessageMediaResponse(
    val id: String,
    val mimeType: String,
    val url: String,
    val urlExpiresInSeconds: Long,
    val durationSeconds: Double? = null,
    val width: Int? = null,
    val height: Int? = null,
)

@Serializable
data class MessageResponse(
    val id: String,
    val conversationId: String,
    val sender: PostAuthorResponse,
    val type: String,
    val text: String? = null,
    val media: MessageMediaResponse? = null,
    val createdAt: String,
    val mineByMe: Boolean,
    /** Yalnızca kendi mesajların için anlamlı; karşı taraf okudu mu. */
    val seenByOther: Boolean = false,
)

@Serializable
data class ConversationResponse(
    val id: String,
    val other: PostAuthorResponse,
    val lastMessage: MessageResponse? = null,
    val unreadCount: Long,
    val lastMessageAt: String? = null,
)

@Serializable
data class ConversationPageResponse(
    val items: List<ConversationResponse>,
    val nextCursor: String? = null,
    val totalUnread: Long,
)

@Serializable
data class MessagePageResponse(
    val items: List<MessageResponse>,
    val nextCursor: String? = null,
)

@Serializable
data class ReadReceiptResponse(
    val conversationId: String,
    val unreadCount: Long,
    val readAt: String,
)
