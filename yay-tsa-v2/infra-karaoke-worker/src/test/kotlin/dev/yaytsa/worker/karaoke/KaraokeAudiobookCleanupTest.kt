package dev.yaytsa.worker.karaoke

import dev.yaytsa.persistence.karaoke.jpa.KaraokeAssetJpaRepository
import dev.yaytsa.persistence.library.repository.LibraryEntityRepository
import dev.yaytsa.testkit.FixedClock
import dev.yaytsa.testkit.InMemoryLibraryQueryPort
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.autoconfigure.domain.EntityScan
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.data.jpa.repository.config.EnableJpaRepositories
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.TestPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@SpringBootApplication
@EntityScan(basePackages = ["dev.yaytsa.persistence.library.entity", "dev.yaytsa.persistence.karaoke.entity"])
@EnableJpaRepositories(basePackages = ["dev.yaytsa.persistence.library.repository", "dev.yaytsa.persistence.karaoke.jpa"])
class KaraokeCleanupTestApplication

// ddl-auto=none so the library entities are not validated against this schema-only fixture; the
// karaoke assets table is created full so KaraokeAssetEntity round-trips. Real db/library gives the
// cross-schema entity_genres/genres anti-join genuine tables to hit.
@SpringBootTest(classes = [KaraokeCleanupTestApplication::class])
@TestPropertySource(
    properties = [
        "spring.flyway.enabled=true",
        "spring.flyway.locations=classpath:db/library",
        "spring.flyway.schemas=core_v2_library",
        "spring.flyway.default-schema=core_v2_library",
        "spring.jpa.hibernate.ddl-auto=none",
    ],
)
class KaraokeAudiobookCleanupTest {
    @Autowired lateinit var libraryEntityRepo: LibraryEntityRepository

    @Autowired lateinit var karaokeRepo: KaraokeAssetJpaRepository

    @Autowired lateinit var jdbc: JdbcTemplate

    private val processor by lazy {
        KaraokeProcessor(
            libraryEntityRepo = libraryEntityRepo,
            libraryQuery = InMemoryLibraryQueryPort(),
            karaokeRepo = karaokeRepo,
            clock = FixedClock(),
            separatorClient = SeparatorClient(),
            outputPath = null,
            demucsCommand = "unsupported",
            separatorUrl = null,
            failThreshold = 3,
            meterRegistry = SimpleMeterRegistry(),
        )
    }

    companion object {
        @JvmStatic
        private val postgres: PostgreSQLContainer<*> =
            PostgreSQLContainer("pgvector/pgvector:pg16")
                .withDatabaseName("karaoke_cleanup_test")
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
        jdbc.execute("CREATE SCHEMA IF NOT EXISTS core_v2_karaoke")
        jdbc.execute(
            "CREATE TABLE IF NOT EXISTS core_v2_karaoke.assets (" +
                "track_id UUID PRIMARY KEY, instrumental_path TEXT, vocal_path TEXT, lyrics_timing TEXT, " +
                "ready_at TIMESTAMPTZ, fail_count INTEGER NOT NULL DEFAULT 0, last_failed_at TIMESTAMPTZ, last_error TEXT)",
        )
        jdbc.execute("TRUNCATE core_v2_karaoke.assets")
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

    private fun seedStems(
        root: Path,
        trackId: UUID,
    ): Pair<Path, Path> {
        val dir = root.resolve(trackId.toString())
        Files.createDirectories(dir)
        val instrumental = dir.resolve("instrumental.wav")
        val vocal = dir.resolve("vocals.wav")
        Files.write(instrumental, byteArrayOf(1))
        Files.write(vocal, byteArrayOf(2))
        jdbc.update(
            "INSERT INTO core_v2_karaoke.assets (track_id, instrumental_path, vocal_path, ready_at) VALUES (?,?,?, now())",
            trackId,
            instrumental.toString(),
            vocal.toString(),
        )
        return instrumental to vocal
    }

    @Test
    fun `purge deletes audiobook stems and rows, leaves music untouched`(
        @TempDir root: Path,
    ) {
        val song = seedTrack("A Song")
        val audiobook = seedTrack("A Chapter")
        tagGenre(audiobook, "Audiobook")
        val (songInstrumental, songVocal) = seedStems(root, song)
        val (bookInstrumental, bookVocal) = seedStems(root, audiobook)

        processor.purgeAudiobookAssets()

        assertFalse(karaokeRepo.existsById(audiobook), "audiobook asset row must be deleted")
        assertFalse(Files.exists(bookInstrumental), "audiobook instrumental stem must be removed from disk")
        assertFalse(Files.exists(bookVocal), "audiobook vocal stem must be removed from disk")
        assertFalse(Files.exists(bookInstrumental.parent), "emptied audiobook stem directory must be removed")

        assertTrue(karaokeRepo.existsById(song), "music asset row must survive")
        assertTrue(Files.exists(songInstrumental), "music instrumental stem must survive")
        assertTrue(Files.exists(songVocal), "music vocal stem must survive")
    }

    @Test
    fun `purge is a no-op when there are no audiobook assets`(
        @TempDir root: Path,
    ) {
        val song = seedTrack("A Song")
        seedStems(root, song)

        processor.purgeAudiobookAssets()

        assertTrue(karaokeRepo.existsById(song), "no music asset may be touched when no audiobooks exist")
    }

    // The sidecar legitimately returns an empty vocal_path for cached instrumental-only results;
    // that must be a READY asset with a null vocal stem, never a failure that burns the retry budget.
    @Test
    fun `separator instrumental-only result is stored as ready with null vocal path`(
        @TempDir root: Path,
    ) {
        val song = seedTrack("A Song")
        val instrumental = root.resolve("instrumental.wav")
        Files.write(instrumental, byteArrayOf(1))

        val responseBody =
            """{"instrumental_path":"$instrumental","vocal_path":"","processing_time_ms":7}"""
                .toByteArray()
        val server =
            com.sun.net.httpserver.HttpServer
                .create(java.net.InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/api/separate") { exchange ->
            exchange.sendResponseHeaders(200, responseBody.size.toLong())
            exchange.responseBody.use { it.write(responseBody) }
        }
        server.start()
        try {
            val libraryQuery = InMemoryLibraryQueryPort()
            libraryQuery.trackFilePaths[dev.yaytsa.shared.EntityId(song.toString())] = root.resolve("song.flac").toString()
            val separatorProcessor =
                KaraokeProcessor(
                    libraryEntityRepo = libraryEntityRepo,
                    libraryQuery = libraryQuery,
                    karaokeRepo = karaokeRepo,
                    clock = FixedClock(),
                    separatorClient = SeparatorClient(),
                    outputPath = null,
                    demucsCommand = "unsupported",
                    separatorUrl = "http://127.0.0.1:${server.address.port}",
                    failThreshold = 3,
                    meterRegistry = SimpleMeterRegistry(),
                )

            separatorProcessor.processTrack(song)

            val asset = karaokeRepo.findById(song).orElseThrow()
            assertTrue(asset.readyAt != null, "instrumental-only result must be READY")
            assertTrue(asset.vocalPath == null, "vocal path must stay null for an instrumental-only result")
            assertTrue(asset.instrumentalPath == instrumental.toString(), "instrumental path must be stored")
            assertTrue(asset.failCount == 0, "instrumental-only result must not consume the retry budget")
        } finally {
            server.stop(0)
        }
    }

    // A user-requested track must be separated right away on the dedicated executor,
    // not wait for the next scheduled batch tick behind a multi-hour backlog.
    @Test
    fun `requestImmediate processes a track without waiting for the scheduled batch`(
        @TempDir root: Path,
    ) {
        val song = seedTrack("A Requested Song")
        val instrumental = root.resolve("instrumental.wav")
        val vocal = root.resolve("vocals.wav")
        Files.write(instrumental, byteArrayOf(1))
        Files.write(vocal, byteArrayOf(2))

        val responseBody =
            """{"instrumental_path":"$instrumental","vocal_path":"$vocal","processing_time_ms":7}"""
                .toByteArray()
        val server =
            com.sun.net.httpserver.HttpServer
                .create(java.net.InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/api/separate") { exchange ->
            exchange.sendResponseHeaders(200, responseBody.size.toLong())
            exchange.responseBody.use { it.write(responseBody) }
        }
        server.start()
        try {
            val libraryQuery = InMemoryLibraryQueryPort()
            libraryQuery.trackFilePaths[dev.yaytsa.shared.EntityId(song.toString())] = root.resolve("song.flac").toString()
            val separatorProcessor =
                KaraokeProcessor(
                    libraryEntityRepo = libraryEntityRepo,
                    libraryQuery = libraryQuery,
                    karaokeRepo = karaokeRepo,
                    clock = FixedClock(),
                    separatorClient = SeparatorClient(),
                    outputPath = null,
                    demucsCommand = "unsupported",
                    separatorUrl = "http://127.0.0.1:${server.address.port}",
                    failThreshold = 3,
                    meterRegistry = SimpleMeterRegistry(),
                )

            separatorProcessor.requestImmediate(dev.yaytsa.shared.TrackId(song.toString()))

            val deadline = System.currentTimeMillis() + 10_000
            var ready = false
            while (System.currentTimeMillis() < deadline && !ready) {
                ready = karaokeRepo.findById(song).map { it.readyAt != null }.orElse(false)
                if (!ready) Thread.sleep(100)
            }
            assertTrue(ready, "requestImmediate must separate the track on the dedicated executor without a scheduler tick")
        } finally {
            server.stop(0)
        }
    }
}
