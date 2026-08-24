package com.nexi.feed

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FeedExperimentTest {

    @Test
    fun `a user always lands in the same arm`() {
        // Kabul kriteri: kullanıcı aynı deney grubunda kalmalı. Atama
        // kimliğin özetinden geldiği için oturum değiştirmek ya da uygulamayı
        // yeniden kurmak grubu kaydırmıyor.
        val experiments = FeedExperiments(
            FeedExperimentConfig(weights = mapOf(FeedVariant.HEURISTIC to 1, FeedVariant.LEARNED to 1))
        )
        val userId = UUID.fromString("11111111-2222-4333-8444-555555555555")

        val assignments = (1..50).map { experiments.variantFor(userId) }.toSet()

        assertEquals(1, assignments.size)
    }

    @Test
    fun `the kill switch sends everyone back to the chronological arm`() {
        // Kabul kriteri: model tek ayarla anında kapatılabilmeli.
        val experiments = FeedExperiments(
            FeedExperimentConfig(
                enabled = false,
                weights = mapOf(FeedVariant.HEURISTIC to 1, FeedVariant.LEARNED to 1),
                shadow = FeedVariant.HEURISTIC,
            )
        )

        repeat(30) {
            val userId = UUID.randomUUID()
            assertEquals(FeedVariant.CONTROL, experiments.variantFor(userId))
            assertNull(experiments.shadowFor(userId), "kapalıyken gölge de koşmamalı")
        }
    }

    @Test
    fun `weights split the population roughly as asked`() {
        val experiments = FeedExperiments(
            FeedExperimentConfig(
                weights = mapOf(FeedVariant.CONTROL to 1, FeedVariant.HEURISTIC to 9)
            )
        )

        val counts = (1..2_000)
            .map { experiments.variantFor(UUID.randomUUID()) }
            .groupingBy { it }
            .eachCount()

        val controlShare = counts.getOrDefault(FeedVariant.CONTROL, 0) / 2_000.0
        assertTrue(controlShare in 0.06..0.14, "kontrol payı ~%10 olmalı, ölçülen: $controlShare")
    }

    @Test
    fun `a zero weight arm never receives anyone`() {
        val experiments = FeedExperiments(
            FeedExperimentConfig(
                weights = mapOf(FeedVariant.CONTROL to 0, FeedVariant.HEURISTIC to 1)
            )
        )

        repeat(200) {
            assertEquals(FeedVariant.HEURISTIC, experiments.variantFor(UUID.randomUUID()))
        }
    }

    @Test
    fun `changing the salt reshuffles the groups`() {
        // Grupları kasten kaydırmak isteyince tek ayar yetmeli.
        val userIds = (1..200).map { UUID.randomUUID() }
        val first = FeedExperiments(
            FeedExperimentConfig(
                weights = mapOf(FeedVariant.CONTROL to 1, FeedVariant.HEURISTIC to 1),
                salt = "deney-a",
            )
        )
        val second = FeedExperiments(
            FeedExperimentConfig(
                weights = mapOf(FeedVariant.CONTROL to 1, FeedVariant.HEURISTIC to 1),
                salt = "deney-b",
            )
        )

        val moved = userIds.count { first.variantFor(it) != second.variantFor(it) }
        assertTrue(moved > 50, "tuz değişince gruplar kaymalı, kayan: $moved")
    }

    @Test
    fun `the shadow arm is skipped when it equals the served arm`() {
        // Gösterilenle aynı kolu gölgede tekrar hesaplamak boşa iş.
        val experiments = FeedExperiments(
            FeedExperimentConfig(
                weights = mapOf(FeedVariant.HEURISTIC to 1),
                shadow = FeedVariant.HEURISTIC,
            )
        )

        assertNull(experiments.shadowFor(UUID.randomUUID()))
    }

    @Test
    fun `the shadow arm runs when it differs from the served arm`() {
        val experiments = FeedExperiments(
            FeedExperimentConfig(
                weights = mapOf(FeedVariant.HEURISTIC to 1),
                shadow = FeedVariant.LEARNED,
            )
        )

        assertEquals(FeedVariant.LEARNED, experiments.shadowFor(UUID.randomUUID()))
    }

    // -------------------------------------------------------- yapilandirma

    @Test
    fun `the configuration is read from a single string`() {
        val config = FeedExperimentConfig.parse("control:1,heuristic:9", true, "learned", "tuz")

        assertEquals(mapOf(FeedVariant.CONTROL to 1, FeedVariant.HEURISTIC to 9), config.weights)
        assertEquals(FeedVariant.LEARNED, config.shadow)
        assertEquals("tuz", config.salt)
    }

    @Test
    fun `a malformed configuration is refused instead of silently ignored`() {
        // Yanlış yazılmış bir ayarın kullanıcıların yarısını başka bir kola
        // atması, fark edilmesi en zor hatalardan biri olurdu.
        listOf("heuristic", "heuristic:abc", "bilinmeyen:1", "heuristic:-1").forEach { raw ->
            assertFailsWith<IllegalArgumentException>("'$raw' reddedilmeli") {
                FeedExperimentConfig.parse(raw, true, null, "tuz")
            }
        }
        assertFailsWith<IllegalArgumentException> {
            FeedExperimentConfig.parse("heuristic:1", true, "yokboyle", "tuz")
        }
    }

    @Test
    fun `an empty configuration falls back to the heuristic arm`() {
        listOf(null, "", "   ").forEach { raw ->
            val config = FeedExperimentConfig.parse(raw, true, null, "tuz")
            assertEquals(mapOf(FeedVariant.HEURISTIC to 1), config.weights)
            assertNull(config.shadow)
        }
    }
}
