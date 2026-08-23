package com.nexi.topics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TopicResolverTest {
    private val repository = InMemoryTopicRepository()
    private val resolver = TopicResolver(repository)

    @Test
    fun `a slug resolves to itself`() {
        assertEquals("teknoloji", resolver.canonicalSlug("teknoloji"))
        assertEquals("yapay-zeka", resolver.canonicalSlug("yapay-zeka"))
    }

    @Test
    fun `a topic id resolves to its slug so clients may send either`() {
        val oyun = repository.topic("oyun")

        assertEquals("oyun", resolver.canonicalSlug(oyun.id.toString()))
        assertEquals("oyun", resolver.canonicalSlug(oyun.id.toString().uppercase()))
    }

    @Test
    fun `uppercase input survives the Turkish dotless i`() {
        // Türkçe yerel ayarında "TEKNOLOJI".lowercase() noktasız "teknolojı"
        // üretir ve hiçbir slug'a denk gelmez; karşılaştırma ROOT ile yapılmalı.
        assertEquals("teknoloji", resolver.canonicalSlug("TEKNOLOJI"))
        assertEquals("egitim", resolver.canonicalSlug("EGITIM"))
        assertEquals("girisimcilik", resolver.canonicalSlug("GIRISIMCILIK"))
    }

    @Test
    fun `the topic prefix and surrounding spaces are tolerated`() {
        assertEquals("bilim", resolver.canonicalSlug("  topic:bilim  "))
    }

    @Test
    fun `values outside the catalog do not resolve`() {
        // Mobil uygulamaların gönderdiği eski İngilizce kimlikler.
        listOf("technology", "gaming", "comedy", "", "   ", "topic:").forEach { unknown ->
            assertNull(resolver.canonicalSlug(unknown), "'$unknown' çözülmemeli")
        }
    }

    @Test
    fun `a deactivated topic stops resolving after a refresh`() {
        val seyahat = repository.topic("seyahat")
        assertEquals("seyahat", resolver.canonicalSlug("seyahat"))

        repository.deactivate(seyahat)
        resolver.refresh()

        assertNull(resolver.canonicalSlug("seyahat"))
        assertNull(resolver.canonicalSlug(seyahat.id.toString()))
    }

    @Test
    fun `the catalog in code matches the catalog the repository serves`() {
        assertEquals(TopicCatalog.SLUGS, resolver.activeSlugs())
        assertTrue(repository.catalogTopics.all { TopicCatalog.label(it.slug) == it.name })
    }
}
