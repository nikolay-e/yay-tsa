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
        "yaytsa.metadata.batch-size=2",
        "yaytsa.metadata.cover-retry-cooldown-hours=1",
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

    @Autowired lateinit var clock: dev.yaytsa.application.shared.port.Clock

    private val testClock get() = clock as MutableTestClock

    companion object {
        private val jpeg =
            byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0x00, 0x10, 0xFF.toByte(), 0xD9.toByte())

        private val coverArtHits = AtomicInteger()
        private val openLibrarySearchHits = AtomicInteger()
        private val openLibraryCoverHits = AtomicInteger()
        private val itunesHits = AtomicInteger()
        private val deezerHits = AtomicInteger()

        @JvmStatic
        val mockServer: HttpServer =
            HttpServer.create(InetSocketAddress(0), 0).apply {
                createContext("/coverart/") { ex ->
                    coverArtHits.incrementAndGet()
                    if (ex.requestURI.path.contains("missing")) {
                        ex.sendResponseHeaders(404, -1)
                        ex.responseBody.close()
                    } else if (ex.requestURI.path.contains("transient")) {
                        ex.sendResponseHeaders(503, -1)
                        ex.responseBody.close()
                    } else {
                        ex.responseHeaders.add("Content-Type", "image/jpeg")
                        ex.sendResponseHeaders(200, jpeg.size.toLong())
                        ex.responseBody.use { it.write(jpeg) }
                    }
                }
                createContext("/mb/") { ex ->
                    val body = """{"release-groups":[],"artists":[]}""".toByteArray()
                    ex.responseHeaders.add("Content-Type", "application/json")
                    ex.sendResponseHeaders(200, body.size.toLong())
                    ex.responseBody.use { it.write(body) }
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
                // iTunes / Deezer free-text fallbacks: empty by default so existing coverless/parked
                // specs are unaffected; a match is returned only for the sentinel album names below.
                createContext("/itunes/search") { ex ->
                    itunesHits.incrementAndGet()
                    val q = ex.requestURI.query ?: ""
                    val self = "http://localhost:${mockServer.address.port}"
                    val body =
                        if (q.contains("ItunesOnly", ignoreCase = true)) {
                            """{"results":[{"artworkUrl100":"$self/itunes-art/100x100bb.jpg"}]}"""
                        } else {
                            """{"results":[]}"""
                        }.toByteArray()
                    ex.responseHeaders.add("Content-Type", "application/json")
                    ex.sendResponseHeaders(200, body.size.toLong())
                    ex.responseBody.use { it.write(body) }
                }
                createContext("/itunes-art/") { ex ->
                    ex.responseHeaders.add("Content-Type", "image/jpeg")
                    ex.sendResponseHeaders(200, jpeg.size.toLong())
                    ex.responseBody.use { it.write(jpeg) }
                }
                createContext("/deezer/search/album") { ex ->
                    deezerHits.incrementAndGet()
                    val q = ex.requestURI.query ?: ""
                    val self = "http://localhost:${mockServer.address.port}"
                    val body =
                        if (q.contains("DeezerOnly", ignoreCase = true)) {
                            """{"data":[{"cover_xl":"$self/deezer-art/cover.jpg"}]}"""
                        } else {
                            """{"data":[]}"""
                        }.toByteArray()
                    ex.responseHeaders.add("Content-Type", "application/json")
                    ex.sendResponseHeaders(200, body.size.toLong())
                    ex.responseBody.use { it.write(body) }
                }
                createContext("/deezer-art/") { ex ->
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
            registry.add("yaytsa.metadata.musicbrainz-base-url") { "$baseUrl/mb" }
            registry.add("yaytsa.metadata.coverart-base-url") { "$baseUrl/coverart" }
            registry.add("yaytsa.metadata.openlibrary-base-url") { "$baseUrl/openlibrary" }
            registry.add("yaytsa.metadata.openlibrary-covers-base-url") { "$baseUrl/openlibrary-covers" }
            registry.add("yaytsa.metadata.itunes-base-url") { "$baseUrl/itunes" }
            registry.add("yaytsa.metadata.deezer-base-url") { "$baseUrl/deezer" }
        }
    }

    @BeforeEach
    fun clean() {
        jdbc.execute("TRUNCATE TABLE core_v2_library.entities CASCADE")
        jdbc.execute("TRUNCATE TABLE core_v2_library.genres CASCADE")
        coverArtHits.set(0)
        openLibrarySearchHits.set(0)
        openLibraryCoverHits.set(0)
        itunesHits.set(0)
        deezerHits.set(0)
        // Keep the clock monotonic across tests: a per-test forward jump prevents the shared RateLimiter's
        // lastRequestAt (set in a prior test that advanced time) from forcing a multi-hour sleep.
        testClock.advance(
            java.time.Duration
                .ofDays(30)
                .seconds,
        )
    }

    @AfterEach
    fun resetFlag() {
        System.clearProperty("ignore")
    }

    private fun seedAudiobookTrack(
        title: String,
        albumId: UUID?,
        artistId: UUID?,
        id: UUID = UUID.randomUUID(),
    ): UUID {
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

    @Test
    fun `permanently coverless track is not re-hammered next cycle, then eligible again after the cooldown window`() {
        val album = seedAlbum("Lost Tome", null, "rg-missing-1")
        seedAudiobookTrack("Unfindable Chapter", album, null)

        enricher.enrich()
        assertTrue(coverArtHits.get() >= 1, "first cycle must attempt the CAA fetch")
        val afterFirst = coverArtHits.get()

        enricher.enrich()
        assertEquals(afterFirst, coverArtHits.get(), "second immediate cycle must NOT re-attempt a parked coverless entity")

        testClock.advance(2 * 3600)
        enricher.enrich()
        assertTrue(coverArtHits.get() > afterFirst, "after the cooldown window the entity is eligible and re-attempted")
    }

    @Test
    fun `art-less entity beyond the first batch is eventually processed once head failures are parked`() {
        // batch-size=2: a deterministic UUID order puts two permanent failures ahead of the resolvable one.
        val headA = seedAlbum("Dead End A", null, "rg-missing-a")
        val headB = seedAlbum("Dead End B", null, "rg-missing-b")
        val resolvable = seedAlbum("Reachable", null, "rg-present-1")

        seedAudiobookTrack("Fail A", headA, null, id = UUID.fromString("00000000-0000-0000-0000-000000000001"))
        seedAudiobookTrack("Fail B", headB, null, id = UUID.fromString("00000000-0000-0000-0000-000000000002"))
        val tail = seedAudiobookTrack("Tail Track", resolvable, null, id = UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff"))

        // First cycle: only the first batch-size head failures are reached (OFFSET 0, ORDER BY entity_id);
        // they get parked. The tail track is still uncovered.
        enricher.enrich()
        assertNull(imageRepo.findByEntityIdAndIsPrimaryTrue(tail), "tail not reachable while head fills the first batch")

        // Second cycle: parked head failures drop out, so the tail track is now in the candidate window.
        enricher.enrich()
        assertNotNull(imageRepo.findByEntityIdAndIsPrimaryTrue(tail), "tail art-less entity must eventually be processed")
    }

    @Test
    fun `head track whose provider is unavailable is parked so a tail track is still reached`() {
        // A provider timeout/5xx (MetadataProviderUnavailableException) must park the head entity too —
        // otherwise it sits at the OFFSET-0 head every cycle, re-hammering the provider and starving the tail.
        val headA = seedAlbum("Timeout Tome A", null, "rg-transient-a")
        val headB = seedAlbum("Timeout Tome B", null, "rg-transient-b")
        val resolvable = seedAlbum("Reachable Tome", null, "rg-present-9")

        seedAudiobookTrack("Failing Chapter A", headA, null, id = UUID.fromString("00000000-0000-0000-0000-000000000001"))
        seedAudiobookTrack("Failing Chapter B", headB, null, id = UUID.fromString("00000000-0000-0000-0000-000000000002"))
        val tail = seedAudiobookTrack("Tail Track", resolvable, null, id = UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff"))

        // First cycle: only the first batch-size head failures are reached (OFFSET 0, ORDER BY entity_id);
        // they throw provider-unavailable and must be parked despite the exception. The tail is still uncovered.
        enricher.enrich()
        assertNull(imageRepo.findByEntityIdAndIsPrimaryTrue(tail), "tail not reachable while head fills the first batch")

        // Second cycle: parked provider-unavailable head failures drop out, so the tail is now in the window.
        enricher.enrich()
        assertNotNull(imageRepo.findByEntityIdAndIsPrimaryTrue(tail), "tail must be reached once provider-unavailable heads are parked")
    }

    @Test
    fun `album with no MusicBrainz match gets a cover from the iTunes fallback`() {
        val album = seedAlbum("ItunesOnly Album", null, null)

        enricher.enrich()

        val cover = imageRepo.findByEntityIdAndIsPrimaryTrue(album)
        assertNotNull(cover, "album must get a Primary image from the iTunes fallback when MusicBrainz has no match")
        assertTrue(itunesHits.get() >= 1, "iTunes search must have been queried for the coverless album")
    }

    @Test
    fun `album missed by MusicBrainz and iTunes still gets a cover from the Deezer fallback`() {
        val album = seedAlbum("DeezerOnly Album", null, null)

        enricher.enrich()

        val cover = imageRepo.findByEntityIdAndIsPrimaryTrue(album)
        assertNotNull(cover, "album must fall through to Deezer when MusicBrainz and iTunes both miss")
        assertTrue(deezerHits.get() >= 1, "Deezer search must have been queried after the iTunes miss")
    }
}
