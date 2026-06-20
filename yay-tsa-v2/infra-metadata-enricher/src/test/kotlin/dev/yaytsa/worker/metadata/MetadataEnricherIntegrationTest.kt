package dev.yaytsa.worker.metadata

import com.sun.net.httpserver.HttpServer
import dev.yaytsa.persistence.library.entity.ImageJpa
import dev.yaytsa.persistence.library.repository.ImageRepository
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
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

@SpringBootTest(classes = [EnricherTestApplication::class])
@TestPropertySource(
    properties = [
        "yaytsa.metadata.enabled=true",
        "yaytsa.metadata.rate-limit-ms=0",
        "spring.flyway.enabled=true",
        "spring.flyway.locations=classpath:db/library",
        "spring.flyway.schemas=core_v2_library",
        "spring.flyway.default-schema=core_v2_library",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.jpa.properties.hibernate.default_schema=core_v2_library",
    ],
)
class MetadataEnricherIntegrationTest {
    @Autowired lateinit var enricher: MetadataEnricher

    @Autowired lateinit var imageRepo: ImageRepository

    @Autowired lateinit var jdbc: JdbcTemplate

    companion object {
        private val jpeg =
            byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0x00, 0x10, 0xFF.toByte(), 0xD9.toByte())

        private val coverArtHits = AtomicInteger()
        private val openLibrarySearchHits = AtomicInteger()
        private val openLibraryCoverHits = AtomicInteger()

        @JvmStatic
        val mockServer: HttpServer =
            HttpServer.create(InetSocketAddress(0), 0).apply {
                createContext("/coverart/") { ex ->
                    coverArtHits.incrementAndGet()
                    ex.responseHeaders.add("Content-Type", "image/jpeg")
                    ex.sendResponseHeaders(200, jpeg.size.toLong())
                    ex.responseBody.use { it.write(jpeg) }
                }
                createContext("/openlibrary/search.json") { ex ->
                    openLibrarySearchHits.incrementAndGet()
                    val body = """{"docs":[{"cover_i":12345}]}""".toByteArray()
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
        val coverCacheDir: Path = Files.createTempDirectory("enricher-cover-cache")

        @JvmStatic
        private val postgres: PostgreSQLContainer<*> =
            PostgreSQLContainer("pgvector/pgvector:pg16")
                .withDatabaseName("enricher_test")
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
            registry.add("yaytsa.metadata.coverart-base-url") { "$baseUrl/coverart" }
            registry.add("yaytsa.metadata.openlibrary-base-url") { "$baseUrl/openlibrary" }
            registry.add("yaytsa.metadata.openlibrary-covers-base-url") { "$baseUrl/openlibrary-covers" }
        }
    }

    @BeforeEach
    fun clean() {
        jdbc.execute("TRUNCATE TABLE core_v2_library.entities CASCADE")
        jdbc.execute("TRUNCATE TABLE core_v2_library.genres CASCADE")
        coverArtHits.set(0)
        openLibrarySearchHits.set(0)
        openLibraryCoverHits.set(0)
    }

    @AfterEach
    fun resetFlag() {
        System.clearProperty("ignore")
    }

    private fun seedAudiobookTrack(
        title: String,
        albumId: UUID?,
        artistId: UUID?,
    ): UUID {
        val id = UUID.randomUUID()
        jdbc.update(
            "INSERT INTO core_v2_library.entities (id, entity_type, name, sort_name, parent_id, search_text) VALUES (?,?,?,?,?,?)",
            id,
            "TRACK",
            title,
            title.lowercase(),
            albumId,
            title.lowercase(),
        )
        jdbc.update(
            "INSERT INTO core_v2_library.audio_tracks (entity_id, album_id, album_artist_id, duration_ms) VALUES (?,?,?,?)",
            id,
            albumId,
            artistId,
            600000L,
        )
        val genreId = UUID.randomUUID()
        jdbc.update("INSERT INTO core_v2_library.genres (id, name) VALUES (?, ?) ON CONFLICT (name) DO NOTHING", genreId, "Audiobook")
        val resolvedGenreId =
            jdbc.queryForObject("SELECT id FROM core_v2_library.genres WHERE name = ?", UUID::class.java, "Audiobook")
        jdbc.update("INSERT INTO core_v2_library.entity_genres (entity_id, genre_id) VALUES (?, ?)", id, resolvedGenreId)
        return id
    }

    private fun seedAlbum(
        name: String,
        artistId: UUID?,
        releaseGroupMbid: String?,
    ): UUID {
        val id = UUID.randomUUID()
        jdbc.update(
            "INSERT INTO core_v2_library.entities (id, entity_type, name, sort_name, parent_id, search_text) VALUES (?,?,?,?,?,?)",
            id,
            "ALBUM",
            name,
            name.lowercase(),
            artistId,
            name.lowercase(),
        )
        jdbc.update(
            "INSERT INTO core_v2_library.albums (entity_id, artist_id, release_group_mbid) VALUES (?,?,?)",
            id,
            artistId,
            releaseGroupMbid,
        )
        return id
    }

    @Test
    fun `audiobook track lacking art fetches a cover from Cover Art Archive into the cover cache`() {
        val album = seedAlbum("Kashchey", null, "rg-mbid-1")
        val track = seedAudiobookTrack("Chapter One", album, null)

        enricher.enrich()

        val cover = imageRepo.findByEntityIdAndIsPrimaryTrue(track)
        assertNotNull(cover, "audiobook track must get a Primary image from the CAA fallback")
        assertTrue(cover!!.path.contains("enricher-cover-cache"), "cover must be cached in the writable cover dir, was ${cover.path}")
        assertTrue(coverArtHits.get() >= 1, "the CAA endpoint must have been called")
    }

    @Test
    fun `audiobook track borrows the parent album cover when one already exists, without any network call`() {
        val album = seedAlbum("Vosstavshiy", null, null)
        val existingCover = coverCacheDir.resolve("album-cover.jpg")
        Files.write(existingCover, jpeg)
        imageRepo.save(
            ImageJpa(
                id = UUID.randomUUID(),
                entityId = album,
                imageType = "Primary",
                path = existingCover.toAbsolutePath().toString(),
                isPrimary = true,
            ),
        )
        val track = seedAudiobookTrack("Part One", album, null)

        enricher.enrich()

        val cover = imageRepo.findByEntityIdAndIsPrimaryTrue(track)
        assertNotNull(cover, "audiobook track must borrow the parent album cover")
        assertEquals(0, coverArtHits.get(), "no external call when the parent cover can be borrowed")
        assertEquals(0, openLibrarySearchHits.get(), "no Open Library call when the parent cover can be borrowed")
    }

    @Test
    fun `image-driven backfill writes a primary image even though no metadata_checked_at gate is used`() {
        val album = seedAlbum("Kashchey", null, "rg-mbid-2")
        val track = seedAudiobookTrack("Chapter Two", album, null)
        // No images row exists and there is no metadata_checked_at involvement for the audiobook pass.
        assertNull(imageRepo.findByEntityIdAndIsPrimaryTrue(track), "precondition: no image row yet")

        enricher.enrich()

        assertNotNull(imageRepo.findByEntityIdAndIsPrimaryTrue(track), "image-driven backfill must fill the missing cover")
    }
}
