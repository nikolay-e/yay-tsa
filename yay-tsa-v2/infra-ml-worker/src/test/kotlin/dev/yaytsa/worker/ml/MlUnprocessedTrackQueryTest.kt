package dev.yaytsa.worker.ml

import dev.yaytsa.persistence.library.entity.LibraryEntityJpa
import dev.yaytsa.persistence.library.repository.LibraryEntityRepository
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.TestPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// Uses its OWN container (not the shared reused one): the query under test anti-joins
// core_v2_library.entities against core_v2_ml.track_features, and creating a minimal
// core_v2_ml.track_features on the shared container would collide with the ml module's
// Flyway migrations running in a parallel test JVM.
@SpringBootTest(classes = [MlWorkerTestApplication::class])
@TestPropertySource(
    properties = [
        "spring.flyway.enabled=true",
        "spring.flyway.locations=classpath:db/library",
        "spring.flyway.schemas=core_v2_library",
        "spring.flyway.default-schema=core_v2_library",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.jpa.properties.hibernate.default_schema=core_v2_library",
    ],
)
class MlUnprocessedTrackQueryTest {
    @Autowired lateinit var entityRepo: LibraryEntityRepository

    @Autowired lateinit var jdbc: JdbcTemplate

    companion object {
        private val NIL_UUID = UUID(0, 0)

        @JvmStatic
        private val postgres: PostgreSQLContainer<*> =
            PostgreSQLContainer("pgvector/pgvector:pg16")
                .withDatabaseName("ml_worker_test")
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

    private fun seedEntity(
        entityType: String,
        name: String,
    ): UUID {
        val id = UUID.randomUUID()
        entityRepo.save(
            LibraryEntityJpa(
                id = id,
                entityType = entityType,
                name = name,
                sortName = name.lowercase(),
                searchText = name.lowercase(),
            ),
        )
        return id
    }

    @Test
    fun `returns only tracks without features rows`() {
        val processed = seedEntity("TRACK", "Processed")
        val unprocessedA = seedEntity("TRACK", "Unprocessed A")
        val unprocessedB = seedEntity("TRACK", "Unprocessed B")
        seedEntity("ALBUM", "Not a track")
        jdbc.update("INSERT INTO core_v2_ml.track_features (track_id) VALUES (?)", processed)

        val unprocessed = entityRepo.findMlUnprocessedTrackIds(NIL_UUID, 50)

        assertEquals(setOf(unprocessedA, unprocessedB), unprocessed.toSet())
    }

    @Test
    fun `limit bounds the batch and keyset pagination walks all unprocessed tracks exactly once`() {
        val trackIds = (1..3).map { seedEntity("TRACK", "Track $it") }.toSet()

        val visited = mutableListOf<UUID>()
        var afterId = NIL_UUID
        while (true) {
            val batch = entityRepo.findMlUnprocessedTrackIds(afterId, 2)
            if (batch.isEmpty()) break
            assertTrue(batch.size <= 2, "limit must bound the batch")
            visited += batch
            afterId = batch.last()
        }

        assertEquals(trackIds, visited.toSet())
        assertEquals(trackIds.size, visited.size, "keyset pagination must not revisit ids")
    }
}
