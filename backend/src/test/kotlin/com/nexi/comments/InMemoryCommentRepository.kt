package com.nexi.comments

import com.nexi.posts.PostAuthorResponse
import java.time.Instant
import java.util.UUID

internal class InMemoryCommentRepository(private val baseTime: Instant) : CommentRepository {
    private val comments = linkedMapOf<UUID, Comment>()

    /** postId -> ownerId. Gönderi burada yoksa "yok" sayılır. */
    val posts = mutableMapOf<UUID, UUID>()

    private var sequence = 0L

    fun addPost(ownerId: UUID): UUID = UUID.randomUUID().also { posts[it] = ownerId }

    override fun create(comment: Comment): CommentDetails? {
        if (comment.postId !in posts) return null
        // Her yeni yorum bir saniye sonraya düşsün ki sıralama belirli olsun.
        val stored = comment.copy(
            createdAt = baseTime.plusSeconds(sequence++),
            updatedAt = baseTime.plusSeconds(sequence),
        )
        comments[stored.id] = stored
        return details(stored)
    }

    override fun page(postId: UUID, viewerId: UUID, cursor: CommentCursor?, limit: Int): List<CommentDetails> = comments.values
        .filter { it.postId == postId && it.status == CommentStatus.PUBLISHED }
        .sortedWith(compareBy<Comment> { it.createdAt }.thenBy { it.id })
        .filter {
            cursor == null ||
                it.createdAt > cursor.createdAt ||
                (it.createdAt == cursor.createdAt && it.id > cursor.id)
        }
        .take(limit)
        .map(::details)

    override fun countForPost(postId: UUID, viewerId: UUID): Long = comments.values
        .count { it.postId == postId && it.status == CommentStatus.PUBLISHED }
        .toLong()

    override fun findById(commentId: UUID): Comment? = comments[commentId]

    override fun postOwner(postId: UUID): UUID? = posts[postId]

    override fun markDeleted(commentId: UUID, now: Instant): Boolean {
        val comment = comments[commentId] ?: return false
        if (comment.status != CommentStatus.PUBLISHED) return false
        comments[commentId] = comment.copy(status = CommentStatus.DELETED, updatedAt = now)
        return true
    }

    private fun details(comment: Comment) = CommentDetails(
        comment = comment,
        author = PostAuthorResponse(comment.authorId.toString(), "Test Kullanıcı", "test"),
    )
}
