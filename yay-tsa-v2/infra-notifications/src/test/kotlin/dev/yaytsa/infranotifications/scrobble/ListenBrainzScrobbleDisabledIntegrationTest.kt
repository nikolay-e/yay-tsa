package dev.yaytsa.infranotifications.scrobble

import com.sun.net.httpserver.HttpServer
import dev.yaytsa.persistence.playback.entity.PlayHistoryEntity
import dev.yaytsa.persistence.playback.jpa.PlayHistoryJpaRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.TestPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import java.net.InetSocketAddress
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

@SpringBootTest(classes = [ScrobbleTestApplication::class])
@TestPropertySource(
    properties = [
        "yaytsa.scrobbling.listenbrainz.enabled=false",
        "yaytsa.scrobbling.listenbrainz.token=test-token",
        "spring.flyway.enabled=true",
        "spring.flyway.locations=classpath:db/playback",
        "spring.flyway.schemas=core_v2_playback",
        "spring.flyway.default-schema=core_v2_playback",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.jpa.properties.hibernate.default_schema=core_v2_playback",
    ],
)
class ListenBrainzScrobbleDisabledIntegrationTest {
    @Autowired lateinit var applicationContext: ApplicationContext

    @Autowired lateinit var playHistoryJpa: PlayHistoryJpaRepository

    companion object {
        private val hits = AtomicInteger()

        @JvmStatic
        val listenBrainz: HttpServer =
            HttpServer.create(InetSocketAddress(0), 0).apply {
                createContext("/") { exchange ->
                    hits.incrementAndGet()
                    val response = """{"status":"ok"}""".toByteArray()
                    exchange.sendResponseHeaders(200, response.size.toLong())
                    exchange.responseBody.use { it.write(response) }
                }
                start()
            }

        @JvmStatic
        private val postgres: PostgreSQLContainer<*> =
            PostgreSQLContainer("pgvector/pgvector:pg16")
                .withDatabaseName("scrobble_disabled_test")
                .withUsername("test")
                .withPassword("test")

        init {
            postgres.start()
        }

        @JvmStatic
        @DynamicPropertySource
        fun props(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { postgres.jdbcUrl }
            registry.add("spring.datasource.username") { postgres.username }
            registry.add("spring.datasource.password") { postgres.password }
            registry.add("yaytsa.scrobbling.listenbrainz.api-url") { "http://localhost:${listenBrainz.address.port}" }
        }
    }

    @Test
    fun `submitter bean is absent and no HTTP call is made for pending plays`() {
        val playId =
            playHistoryJpa
                .save(
                    PlayHistoryEntity(
                        userId = "user-1",
                        itemId = UUID.randomUUID().toString(),
                        startedAt = Instant.now().minusSeconds(600),
                        durationMs = 0,
                        playedMs = 387_000,
                        completed = true,
                        scrobbled = false,
                        skipped = false,
                        recordedAt = Instant.now(),
                    ),
                ).id

        assertTrue(applicationContext.getBeanNamesForType(ListenBrainzScrobbleSubmitter::class.java).isEmpty())
        assertEquals(0, hits.get())
        assertFalse(playHistoryJpa.findById(playId).get().scrobbled)
    }
}
