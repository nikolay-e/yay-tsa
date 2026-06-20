package dev.yaytsa.worker.metadata

import com.sun.net.httpserver.HttpServer
import dev.yaytsa.persistence.library.repository.ImageRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.TestPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

// The Open Library fallback is gated behind yaytsa.metadata.openlibrary-enabled. This class enables it
// and forces every prior source to miss (MusicBrainz returns no release-group, no parent cover to
// borrow), so the only way the track gets a cover is the Open Library path. The sibling
// MetadataEnricherIntegrationTest leaves the flag at its default (false); together they assert the
// path only runs when the flag is on.
@SpringBootTest(classes = [EnricherTestApplication::class])
@TestPropertySource(
    properties = [
        "yaytsa.metadata.enabled=true",
        "yaytsa.metadata.rate-limit-ms=0",
        "yaytsa.metadata.openlibrary-enabled=true",
        "spring.flyway.enabled=true",
        "spring.flyway.locations=classpath:db/library",
        "spring.flyway.schemas=core_v2_library",
        "spring.flyway.default-schema=core_v2_library",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.jpa.properties.hibernate.default_schema=core_v2_library",
    ],
)
class OpenLibraryFlagIntegrationTest {
    @Autowired lateinit var enricher: MetadataEnricher

    @Autowired lateinit var imageRepo: ImageRepository

    @Autowired lateinit var jdbc: JdbcTemplate

    companion object {
        private val jpeg =
            byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0x00, 0x10, 0xFF.toByte(), 0xD9.toByte())

        private val openLibrarySearchHits = AtomicInteger()
        private val openLibraryCoverHits = AtomicInteger()

        @JvmStatic
        val mockServer: HttpServer =
            HttpServer.create(InetSocketAddress(0), 0).apply {
                // MusicBrainz: always empty result so the CAA path produces no cover.
                createContext("/mb/release-group") { ex ->
                    val body = """{"release-groups":[]}""".toByteArray()
                    ex.responseHeaders.add("Content-Type", "application/json")
                    ex.sendResponseHeaders(200, body.size.toLong())
                    ex.responseBody.use { it.write(body) }
                }
                createContext("/openlibrary/search.json") { ex ->
                    openLibrarySearchHits.incrementAndGet()
                    val body = """{"docs":[{"cover_i":777}]}""".toByteArray()
                    ex.responseHeaders.add("Content-Type", "application/json")
                    ex.sendResponseHeaders(200, body.size.toLong())
                    ex.responseBody.use { it.write(body) }
                }
                createContext("/openlibrary-covers/b/id/") { ex ->
                    openLibraryCoverHits.incrementAndGet()
                    ex.responseHeaders.add("Content-Type", "image/jpeg")
                    ex.sendResponseHeaders(200, jpeg.size.toLong())
                    ex.responseBody.use { it.write(jpeg) }
                }
                start()
            }

        private val baseUrl: String get() = "http://localhost:${mockServer.address.port}"

        @JvmStatic
        val coverCacheDir: Path = Files.createTempDirectory("openlibrary-cover-cache")

        @JvmStatic
        private val postgres: PostgreSQLContainer<*> =
            PostgreSQLContainer("pgvector/pgvector:pg16")
                .withDatabaseName("openlibrary_test")
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
            registry.add("yaytsa.image.cover-cache-dir") { coverCacheDir.toString() }
            registry.add("yaytsa.metadata.musicbrainz-base-url") { "$baseUrl/mb" }
            registry.add("yaytsa.metadata.openlibrary-base-url") { "$baseUrl/openlibrary" }
            registry.add("yaytsa.metadata.openlibrary-covers-base-url") { "$baseUrl/openlibrary-covers" }
        }
    }

    @BeforeEach
    fun clean() {
        jdbc.execute("TRUNCATE TABLE core_v2_library.entities CASCADE")
        jdbc.execute("TRUNCATE TABLE core_v2_library.genres CASCADE")
        openLibrarySearchHits.set(0)
        openLibraryCoverHits.set(0)
    }

    private fun seedAudiobookTrack(title: String): UUID {
        val album = UUID.randomUUID()
        jdbc.update(
            "INSERT INTO core_v2_library.entities (id, entity_type, name, sort_name, search_text) VALUES (?,?,?,?,?)",
            album,
            "ALBUM",
            "$title (Unabridged)",
            title.lowercase(),
            title.lowercase(),
        )
        jdbc.update("INSERT INTO core_v2_library.albums (entity_id) VALUES (?)", album)
        val id = UUID.randomUUID()
        jdbc.update(
            "INSERT INTO core_v2_library.entities (id, entity_type, name, sort_name, parent_id, search_text) VALUES (?,?,?,?,?,?)",
            id,
            "TRACK",
            title,
            title.lowercase(),
            album,
            title.lowercase(),
        )
        jdbc.update(
            "INSERT INTO core_v2_library.audio_tracks (entity_id, album_id, duration_ms) VALUES (?,?,?)",
            id,
            album,
            600000L,
        )
        val genreId = UUID.randomUUID()
        jdbc.update("INSERT INTO core_v2_library.genres (id, name) VALUES (?, ?) ON CONFLICT (name) DO NOTHING", genreId, "Audiobook")
        val resolvedGenreId =
            jdbc.queryForObject("SELECT id FROM core_v2_library.genres WHERE name = ?", UUID::class.java, "Audiobook")
        jdbc.update("INSERT INTO core_v2_library.entity_genres (entity_id, genre_id) VALUES (?, ?)", id, resolvedGenreId)
        return id
    }

    @Test
    fun `with the flag enabled and all other sources missing, Open Library supplies the audiobook cover`() {
        val track = seedAudiobookTrack("The Lighthouse")
        assertNull(imageRepo.findByEntityIdAndIsPrimaryTrue(track), "precondition: no image row yet")

        enricher.enrich()

        val cover = imageRepo.findByEntityIdAndIsPrimaryTrue(track)
        assertNotNull(cover, "Open Library must supply a cover when enabled and other sources miss")
        assertEquals(1, openLibrarySearchHits.get(), "Open Library search must be queried exactly once")
        assertEquals(1, openLibraryCoverHits.get(), "Open Library cover image must be fetched once")
    }
}
