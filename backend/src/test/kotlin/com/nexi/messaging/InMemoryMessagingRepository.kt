package com.nexi.messaging

import com.nexi.media.MediaAsset
import com.nexi.media.MediaProcessingStatus
import com.nexi.media.MediaStatus
import com.nexi.posts.PostAuthorResponse
import java.time.Instant
import java.util.UUID

internal class InMemoryMessagingRepository(private val baseTime: Instant) : MessagingRepository {
    private data class UserRow(val id: UUID, val fullName: String, val username: String)
    private data class Conversation(val id: UUID, val directKey: String, var lastMessageAt: Instant? = null)

    private val users = mutableMapOf<UUID, UserRow>()
    private val conversations = linkedMapOf<UUID, Conversation>()
    private val members = mutableMapOf<Pair<UUID, UUID>, Instant?>()
    private val messages = linkedMapOf<UUID, Message>()
    private val media = mutableMapOf<UUID, MediaAsset>()

    val blocks = mutableSetOf<Pair<UUID, UUID>>()

    private var sequence = 0L

    fun addUser(fullName: String, username: String): UUID {
        val id = UUID.randomUUID()
        users[id] = UserRow(id, fullName, username)
        return id
    }

    fun idByUsername(username: String): UUID? =
        users.values.firstOrNull { it.username.equals(username, ignoreCase = true) }?.id

    fun addMedia(
        ownerId: UUID,
        mimeType: String = "image/png",
        status: MediaStatus = MediaStatus.READY,
        processing: MediaProcessingStatus = MediaProcessingStatus.READY,
    ): UUID {
        val id = UUID.randomUUID()
        media[id] = MediaAsset(
            id = id,
            ownerId = ownerId,
            storageKey = "users/$ownerId/media/$id",
            originalFilename = "x",
            mimeType = mimeType,
            declaredSizeBytes = 10,
            actualSizeBytes = 10,
            status = status,
            createdAt = baseTime,
            updatedAt = baseTime,
            processingStatus = processing,
        )
        return id
    }

    private fun blocked(a: UUID, b: UUID) = (a to b) in blocks || (b to a) in blocks

    private fun directKey(a: UUID, b: UUID) =
        if (a.toString() < b.toString()) "$a:$b" else "$b:$a"

    override fun findOrCreateDirect(userId: UUID, otherId: UUID, now: Instant): UUID {
        val key = directKey(userId, otherId)
        conversations.values.firstOrNull { it.directKey == key }?.let { return it.id }

        val id = UUID.randomUUID()
        conversations[id] = Conversation(id, key)
        members[id to userId] = null
        members[id to otherId] = null
        return id
    }

    override fun isMember(conversationId: UUID, userId: UUID) = (conversationId to userId) in members

    override fun counterpartId(conversationId: UUID, viewerId: UUID): UUID? = members.keys
        .firstOrNull { it.first == conversationId && it.second != viewerId }
        ?.second

    override fun counterpart(conversationId: UUID, viewerId: UUID): PostAuthorResponse? {
        val otherId = members.keys
            .firstOrNull { it.first == conversationId && it.second != viewerId }
            ?.second ?: return null
        if (blocked(viewerId, otherId)) return null
        return author(otherId)
    }

    override fun conversations(viewerId: UUID, cursor: MessageCursor?, limit: Int): List<ConversationSummary> =
        conversations.values
            .filter { (it.id to viewerId) in members && it.lastMessageAt != null }
            .mapNotNull { conversation ->
                val other = members.keys
                    .firstOrNull { it.first == conversation.id && it.second != viewerId }
                    ?.second ?: return@mapNotNull null
                if (blocked(viewerId, other)) return@mapNotNull null
                ConversationSummary(
                    id = conversation.id,
                    other = author(other),
                    lastMessage = null,
                    unreadCount = unreadCount(conversation.id, viewerId),
                    lastMessageAt = conversation.lastMessageAt,
                )
            }
            .sortedWith(compareByDescending<ConversationSummary> { it.lastMessageAt }.thenByDescending { it.id })
            .filter {
                cursor == null ||
                    (it.lastMessageAt != null && it.lastMessageAt < cursor.createdAt) ||
                    (it.lastMessageAt == cursor.createdAt && it.id < cursor.id)
            }
            .take(limit)

    override fun totalUnread(viewerId: UUID): Long = conversations.values
        .filter { (it.id to viewerId) in members }
        .filterNot { conversation ->
            val other = members.keys.firstOrNull { it.first == conversation.id && it.second != viewerId }?.second
            other != null && blocked(viewerId, other)
        }
        .sumOf { unreadCount(it.id, viewerId) }

    override fun messages(
        conversationId: UUID,
        viewerId: UUID,
        cursor: MessageCursor?,
        limit: Int,
    ): List<MessageDetails> = messages.values
        .filter { it.conversationId == conversationId && it.status == MessageStatus.SENT }
        .sortedWith(compareByDescending<Message> { it.createdAt }.thenByDescending { it.id })
        .filter {
            cursor == null ||
                it.createdAt < cursor.createdAt ||
                (it.createdAt == cursor.createdAt && it.id < cursor.id)
        }
        .take(limit)
        .map(::details)

    override fun findMessage(messageId: UUID, viewerId: UUID): MessageDetails? =
        messages[messageId]?.takeIf { it.status == MessageStatus.SENT }?.let(::details)

    override fun checkMedia(mediaId: UUID, ownerId: UUID): MessageMediaResult {
        val asset = media[mediaId] ?: return MessageMediaResult(MessageMediaCheck.NOT_FOUND)
        val check = when {
            asset.ownerId != ownerId -> MessageMediaCheck.NOT_OWNED
            asset.status != MediaStatus.READY -> MessageMediaCheck.NOT_READY
            asset.processingStatus != MediaProcessingStatus.READY -> MessageMediaCheck.NOT_READY
            else -> MessageMediaCheck.OK
        }
        return MessageMediaResult(check, asset.mimeType)
    }

    override fun send(message: Message): MessageDetails? {
        // Her mesaj bir saniye sonraya dussun ki siralama belirli olsun.
        val stored = message.copy(createdAt = baseTime.plusSeconds(sequence++))
        messages[stored.id] = stored
        conversations[stored.conversationId]?.lastMessageAt = stored.createdAt
        return details(stored)
    }

    override fun markRead(conversationId: UUID, userId: UUID, now: Instant) {
        members[conversationId to userId] = now
    }

    override fun unreadCount(conversationId: UUID, userId: UUID): Long {
        val lastRead = members[conversationId to userId]
        return messages.values.count {
            it.conversationId == conversationId &&
                it.status == MessageStatus.SENT &&
                it.senderId != userId &&
                (lastRead == null || it.createdAt > lastRead)
        }.toLong()
    }

    override fun deleteMessage(messageId: UUID, senderId: UUID, now: Instant): Boolean {
        val message = messages[messageId] ?: return false
        if (message.senderId != senderId || message.status != MessageStatus.SENT) return false
        messages[messageId] = message.copy(status = MessageStatus.DELETED)
        return true
    }

    private fun author(id: UUID): PostAuthorResponse {
        val row = users[id]
        return PostAuthorResponse(
            id = id.toString(),
            fullName = row?.fullName ?: "Bilinmeyen",
            username = row?.username ?: "bilinmeyen",
        )
    }

    private fun details(message: Message): MessageDetails {
        val otherLastRead = members.entries
            .firstOrNull { it.key.first == message.conversationId && it.key.second != message.senderId }
            ?.value
        return MessageDetails(
            message = message,
            sender = author(message.senderId),
            media = message.mediaId?.let(media::get),
            seenByOther = otherLastRead != null && !otherLastRead.isBefore(message.createdAt),
        )
    }
}
