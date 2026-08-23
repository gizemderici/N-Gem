package com.nexi.topics

import com.nexi.auth.ApiException
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TopicServiceTest {
    private val now = Instant.parse("2026-08-23T00:00:00Z")
    private val userId = UUID.randomUUID()
    private val repository = InMemoryTopicRepository()
    private val service = TopicService(repository, Clock.fixed(now, ZoneOffset.UTC))

    private fun ids(vararg slugs: String) = slugs.map { repository.topic(it).id.toString() }

    @Test
    fun `catalog is ordered and advertises the selection limits`() {
        val catalog = service.list()

        assertEquals(TopicService.MIN_TOPICS, catalog.minSelectable)
        assertEquals(TopicService.MAX_TOPICS, catalog.maxSelectable)
        assertEquals("teknoloji", catalog.items.first().slug)
        assertEquals(repository.catalogTopics.size, catalog.items.size)
    }

    @Test
    fun `selection order is preserved exactly as sent`() {
        val saved = service.replace(userId, UpdateUserTopicsRequest(ids("spor", "teknoloji", "egitim")))

        assertEquals(listOf("spor", "teknoloji", "egitim"), saved.items.map { it.slug })
        assertTrue(saved.completed)
        assertEquals(now.toString(), saved.updatedAt)
        assertEquals(listOf("spor", "teknoloji", "egitim"), service.userTopics(userId).items.map { it.slug })
    }

    @Test
    fun `replacing the selection removes the previous one`() {
        service.replace(userId, UpdateUserTopicsRequest(ids("spor", "teknoloji", "egitim")))
        service.replace(userId, UpdateUserTopicsRequest(ids("muzik", "sanat", "oyun", "bilim")))

        assertEquals(listOf("muzik", "sanat", "oyun", "bilim"), service.userTopics(userId).items.map { it.slug })
    }

    @Test
    fun `a user without a selection is not completed yet`() {
        val response = service.userTopics(userId)

        assertTrue(response.items.isEmpty())
        assertFalse(response.completed)
        assertEquals(null, response.updatedAt)
    }

    @Test
    fun `selection is validated before it is stored`() {
        assertEquals("TOO_FEW_TOPICS", assertFailsWith<ApiException> {
            service.replace(userId, UpdateUserTopicsRequest(ids("spor", "teknoloji")))
        }.code)

        assertEquals("TOO_MANY_TOPICS", assertFailsWith<ApiException> {
            service.replace(userId, UpdateUserTopicsRequest(repository.catalogTopics.map { it.id.toString() }))
        }.code)

        assertEquals("DUPLICATE_TOPIC", assertFailsWith<ApiException> {
            service.replace(userId, UpdateUserTopicsRequest(ids("spor", "spor", "egitim")))
        }.code)

        assertEquals("INVALID_TOPIC_ID", assertFailsWith<ApiException> {
            service.replace(userId, UpdateUserTopicsRequest(listOf("bu-bir-uuid-degil", "x", "y")))
        }.code)

        assertEquals("UNKNOWN_TOPIC", assertFailsWith<ApiException> {
            service.replace(
                userId,
                UpdateUserTopicsRequest(ids("spor", "teknoloji") + UUID.randomUUID().toString()),
            )
        }.code)

        assertTrue(service.userTopics(userId).items.isEmpty())
    }

    @Test
    fun `a topic taken out of the catalog can no longer be selected`() {
        repository.deactivate(repository.topic("oyun"))

        assertEquals("UNKNOWN_TOPIC", assertFailsWith<ApiException> {
            service.replace(userId, UpdateUserTopicsRequest(ids("spor", "teknoloji", "oyun")))
        }.code)
        assertFalse(service.list().items.any { it.slug == "oyun" })
    }
}
