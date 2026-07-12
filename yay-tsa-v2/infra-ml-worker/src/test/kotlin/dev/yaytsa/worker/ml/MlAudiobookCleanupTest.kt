package dev.yaytsa.worker.ml

import dev.yaytsa.persistence.ml.jpa.TrackFeaturesJpaRepository
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.autoconfigure.domain.EntityScan
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.data.jpa.repository.config.EnableJpaRepositories
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.TestPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// Deliberately not @SpringBootApplication: a component-scanning app here would be picked up by the
// sibling MlWorkerTestApplication's scan of this package (and vice versa), cross-contaminating the
// managed entity set. Explicit @EntityScan/@EnableJpaRepositories + autoconfig, no @ComponentScan.
@EnableAutoConfiguration
@EntityScan(basePackages = ["dev.yaytsa.persistence.ml.entity"])
@EnableJpaRepositories(basePackages = ["dev.yaytsa.persistence.ml.jpa"])
class MlCleanupTestApplication

// ddl-auto=none so the ml entities are not validated against the minimal track_features table this
// test creates (the cleanup DELETE only touches track_id). Loads the real db/library schema so the
// cross-schema entity_genres/genres anti-join in deleteAudiobookFeatures has genuine tables to hit.
@SpringBootTest(classes = [MlCleanupTestApplication::class])
@TestPropertySource(
    properties = [
        "spring.flyway.enabled=true",
        "spring.flyway.locations=classpath:db/library",
        "spring.flyway.schemas=core_v2_library",
        "spring.flyway.default-schema=core_v2_library",
        "spring.jpa.hibernate.ddl-auto=none",
    ],
)
class MlAudiobookCleanupTest {
    @Autowired lateinit var trackFeaturesRepo: TrackFeaturesJpaRepository

    @Autowired lateinit var jdbc: JdbcTemplate

    companion object {
        @JvmStatic
        private val postgres: PostgreSQLContainer<*> =
            PostgreSQLContainer("pgvector/pgvector:pg16")
                .withDatabaseName("ml_cleanup_test")
                .withUsername("test")
                .withPassword("test")

        init {
            postgres.start()
            postgres.createConnection("").use { c ->
                c.createStatement().use { s ->
                    s.execute("CREATE EXTENSION IF NOT EXISTS citext")
                    s.execute("CREATE EXTENSION IF NOT EXISTS pg_trgm")
                }
            }
        }

        @JvmStatic
        @DynamicPropertySource
        fun props(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { postgres.jdbcUrl + "&stringtype=unspecified" }
            registry.add("spring.datasource.username") { postgres.username }
            registry.add("spring.datasource.password") { postgres.password }
        }
    }

    @BeforeEach
    fun clean() {
        jdbc.execute("TRUNCATE TABLE core_v2_library.entities CASCADE")
        jdbc.execute("CREATE SCHEMA IF NOT EXISTS core_v2_ml")
        jdbc.execute("CREATE TABLE IF NOT EXISTS core_v2_ml.track_features (track_id UUID PRIMARY KEY)")
        jdbc.execute("TRUNCATE core_v2_ml.track_features")
    }

    private fun seedTrack(name: String): UUID {
        val id = UUID.randomUUID()
        jdbc.update(
            "INSERT INTO core_v2_library.entities (id, entity_type, name, sort_name, search_text) VALUES (?,?,?,?,?)",
            id,
            "TRACK",
            name,
            name.lowercase(),
            name.lowercase(),
        )
        jdbc.update("INSERT INTO core_v2_ml.track_features (track_id) VALUES (?)", id)
        return id
    }

    private fun tagGenre(
        entityId: UUID,
        genreName: String,
    ) {
        val genreId = UUID.randomUUID()
        jdbc.update("INSERT INTO core_v2_library.genres (id, name) VALUES (?, ?) ON CONFLICT (name) DO NOTHING", genreId, genreName)
        val resolved = jdbc.queryForObject("SELECT id FROM core_v2_library.genres WHERE name = ?", UUID::class.java, genreName)
        jdbc.update("INSERT INTO core_v2_library.entity_genres (entity_id, genre_id) VALUES (?, ?)", entityId, resolved)
    }

    @Test
    fun `deleteAudiobookFeatures removes only audiobook rows`() {
        val song = seedTrack("A Song")
        val audiobook = seedTrack("A Chapter")
        tagGenre(audiobook, "Audiobook")

        val deleted = trackFeaturesRepo.deleteAudiobookFeatures()

        assertEquals(1, deleted, "exactly the one audiobook feature row must be deleted")
        val survivors =
            jdbc.queryForList("SELECT track_id FROM core_v2_ml.track_features", UUID::class.java)
        assertEquals(listOf(song), survivors, "the non-audiobook feature row must survive")
    }

    @Test
    fun `deleteAudiobookFeatures is a no-op once drained`() {
        seedTrack("A Song")

        assertEquals(0, trackFeaturesRepo.deleteAudiobookFeatures())
        assertTrue(jdbc.queryForList("SELECT track_id FROM core_v2_ml.track_features", UUID::class.java).size == 1)
    }
}
