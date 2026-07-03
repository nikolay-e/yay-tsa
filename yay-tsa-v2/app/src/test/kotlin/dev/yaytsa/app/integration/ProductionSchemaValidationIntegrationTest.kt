package dev.yaytsa.app.integration

import jakarta.persistence.EntityManagerFactory
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import javax.sql.DataSource

@SpringBootTest
class ProductionSchemaValidationIntegrationTest {
    @Autowired
    lateinit var entityManagerFactory: EntityManagerFactory

    @Autowired
    lateinit var dataSource: DataSource

    companion object {
        private val expectedSchemas =
            setOf(
                "core_v2_shared",
                "core_v2_auth",
                "core_v2_playback",
                "core_v2_playlists",
                "core_v2_preferences",
                "core_v2_adaptive",
                "core_v2_library",
                "core_v2_ml",
                "core_v2_karaoke",
                "core_v2_groups",
            )

        @JvmStatic
        val postgres: PostgreSQLContainer<*> =
            PostgreSQLContainer("pgvector/pgvector:pg16")
                .withDatabaseName("yaytsa_validate")
                .withUsername("test")
                .withPassword("test")

        init {
            postgres.start()
        }

        @DynamicPropertySource
        @JvmStatic
        fun configureProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { postgres.jdbcUrl }
            registry.add("spring.datasource.username") { postgres.username }
            registry.add("spring.datasource.password") { postgres.password }
            registry.add("spring.jpa.hibernate.ddl-auto") { "validate" }
            registry.add("spring.cache.cache-names") { "" }
            registry.add("bucket4j.enabled") { "false" }
            registry.add("yaytsa.library.music-path") { System.getProperty("java.io.tmpdir") }
            registry.add("yaytsa.image.cover-cache-dir") {
                java.nio.file.Files
                    .createTempDirectory("yaytsa-validate-cover-cache")
                    .toString()
            }
            registry.add("yaytsa.karaoke.output-path") { System.getProperty("java.io.tmpdir") }
            registry.add("yaytsa.scanner.scheduled-enabled") { "false" }
            registry.add("yaytsa.mpd.enabled") { "false" }
            registry.add("yaytsa.llm.enabled") { "false" }
            registry.add("yaytsa.ml.enabled") { "false" }
            registry.add("yaytsa.karaoke.enabled") { "false" }
            registry.add("yaytsa.lyrics.lrclib.enabled") { "false" }
        }
    }

    @Test
    fun `hibernate validate passes against schemas migrated by production FlywayConfig`() {
        assertTrue(entityManagerFactory.metamodel.entities.isNotEmpty())
        assertEquals(
            "validate",
            entityManagerFactory.properties["hibernate.hbm2ddl.auto"]?.toString()?.lowercase(),
        )
    }

    @Test
    fun `every bounded context schema is migrated`() {
        val migratedSchemas = mutableSetOf<String>()
        dataSource.connection.use { connection ->
            connection
                .prepareStatement(
                    "SELECT table_schema FROM information_schema.tables WHERE table_name = 'flyway_schema_history'",
                ).executeQuery()
                .use { rows ->
                    while (rows.next()) migratedSchemas.add(rows.getString(1))
                }
        }
        assertEquals(expectedSchemas, migratedSchemas)
    }

    @Test
    fun `shared extensions migration installs production extensions in public schema`() {
        val extensions = mutableSetOf<String>()
        dataSource.connection.use { connection ->
            connection
                .prepareStatement(
                    "SELECT extname FROM pg_extension e JOIN pg_namespace n ON e.extnamespace = n.oid WHERE n.nspname = 'public'",
                ).executeQuery()
                .use { rows ->
                    while (rows.next()) extensions.add(rows.getString(1))
                }
        }
        assertTrue(extensions.containsAll(setOf("citext", "pg_trgm", "unaccent", "vector")))
    }
}
