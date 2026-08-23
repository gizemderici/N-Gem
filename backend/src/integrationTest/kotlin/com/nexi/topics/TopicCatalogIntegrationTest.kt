package com.nexi.topics

import com.nexi.config.DatabaseConfig
import com.nexi.config.DatabaseFactory
import com.zaxxer.hikari.HikariDataSource
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.testcontainers.postgresql.PostgreSQLContainer
import kotlin.test.assertEquals

/**
 * [TopicCatalog] ile `V5__create_topics.sql` arasındaki kaymayı yakalar.
 *
 * Katalog iki yerde yaşamak zorunda: sıralayıcı ve etiketler için koddaki
 * liste, kalıcı kimlikler için migration. İkisi ayrışırsa mobil, backend ve
 * çevrimdışı değerlendirici yeniden farklı kimlikler konuşmaya başlar — Faz
 * 0'ın çözdüğü hatanın tam olarak kendisi.
 *
 * Yarış testlerinden ayrı bir sınıf: oradaki `TRUNCATE ... CASCADE` katalog
 * tablosunu da boşalttığı için tohum veri orada okunamaz.
 */
class TopicCatalogIntegrationTest {

    companion object {
        private val postgres = PostgreSQLContainer("postgres:17-alpine")
            .withDatabaseName("nexi_catalog")
            .withUsername("nexi")
            .withPassword("nexi_test_password")

        private lateinit var dataSource: HikariDataSource

        @JvmStatic
        @BeforeAll
        fun startPostgres() {
            postgres.start()
            dataSource = DatabaseFactory.create(
                DatabaseConfig(
                    url = postgres.jdbcUrl,
                    user = postgres.username,
                    password = postgres.password,
                )
            )
        }

        @JvmStatic
        @AfterAll
        fun stopPostgres() {
            if (::dataSource.isInitialized) dataSource.close()
            postgres.stop()
        }
    }

    @Test
    fun `the catalog in code matches the seeded catalog exactly`() {
        val seeded = JdbcTopicRepository(dataSource).listActive()

        assertEquals(
            TopicCatalog.LABELS.keys.toList(),
            seeded.map { it.slug },
            "Slug listesi ve sırası V5 ile aynı olmalı",
        )
        assertEquals(
            TopicCatalog.LABELS,
            seeded.associate { it.slug to it.name },
            "Görünen adlar V5 ile aynı olmalı",
        )
    }

    @Test
    fun `every topic resolves from its slug and from its id`() {
        val resolver = TopicResolver(JdbcTopicRepository(dataSource))

        JdbcTopicRepository(dataSource).listActive().forEach { topic ->
            assertEquals(topic.slug, resolver.canonicalSlug(topic.slug))
            assertEquals(topic.slug, resolver.canonicalSlug(topic.id.toString()))
        }
        assertEquals(TopicCatalog.SLUGS, resolver.activeSlugs())
    }
}
