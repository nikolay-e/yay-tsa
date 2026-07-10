package dev.yaytsa.worker.scanner

import dev.yaytsa.application.library.port.UploadIngestResult
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.TestPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories

// Own container, mirroring LibraryWriterHygieneTest: infra-library-scanner and
// infra-persistence:library both write core_v2_library, and Gradle runs their test
// tasks in parallel - sharing one schema across both would corrupt each other.
@SpringBootTest(classes = [ScannerTestApplication::class])
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
class UploadedTrackIngestTest {
    @Autowired lateinit var writer: LibraryWriter

    companion object {
        @JvmStatic
        private val postgres: PostgreSQLContainer<*> =
            PostgreSQLContainer("pgvector/pgvector:pg16")
                .withDatabaseName("upload_ingest_test")
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
        val coverCacheDir: Path = Files.createTempDirectory("upload-ingest-cover-cache")

        @JvmStatic
        @DynamicPropertySource
        fun props(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { postgres.jdbcUrl + "&stringtype=unspecified" }
            registry.add("spring.datasource.username") { postgres.username }
            registry.add("spring.datasource.password") { postgres.password }
            registry.add("yaytsa.image.cover-cache-dir") { coverCacheDir.toString() }
        }
    }

    @Autowired
    lateinit var jdbc: org.springframework.jdbc.core.JdbcTemplate

    @BeforeEach
    fun clean() {
        // TRUNCATE needs ACCESS EXCLUSIVE; a scanner connection from a sibling test that has
        // not yet released its lock makes the first attempt fail with a lock timeout on slow
        // CI runners. Retry instead of flaking.
        var lastFailure: Exception? = null
        repeat(5) {
            try {
                jdbc.execute("SET lock_timeout = '5s'")
                jdbc.execute("TRUNCATE TABLE core_v2_library.entities CASCADE")
                return
            } catch (e: org.springframework.dao.PessimisticLockingFailureException) {
                lastFailure = e
                Thread.sleep(2_000)
            }
        }
        throw requireNotNull(lastFailure)
    }

    private fun taggedFlac(
        dir: Path,
        albumArtist: String,
        album: String,
    ): Path {
        val file = dir.resolve("upload-source-${System.nanoTime()}.flac")
        javaClass.getResourceAsStream("/fixtures/silent-3s.flac").use { input ->
            Files.copy(requireNotNull(input) { "fixture silent-3s.flac missing" }, file)
        }
        val af = AudioFileIO.read(file.toFile())
        af.tagOrCreateAndSetDefault.setField(FieldKey.ALBUM_ARTIST, albumArtist)
        af.tag.setField(FieldKey.ALBUM, album)
        AudioFileIO.write(af)
        return file
    }

    @Test
    fun `upload whose album-artist tag resolves through a planted symlink outside the library root is rejected and the escaped directory is removed`(
        @org.junit.jupiter.api.io.TempDir tmp: Path,
    ) {
        val outside = tmp.resolve("outside").createDirectories()
        val root = tmp.resolve("music").createDirectories()
        // Simulate a symlink planted at an intermediate path segment (e.g. via an
        // unrelated prior compromise) pointing out of the configured library root.
        Files.createSymbolicLink(root.resolve("Evil Artist"), outside)

        val source = taggedFlac(tmp, albumArtist = "Evil Artist", album = "NewAlbum")
        val ingest = UploadedTrackIngest(writer, root.toString())

        val result = ingest.ingest(source, "track.flac")

        assertEquals(UploadIngestResult.NotIngestable, result, "an upload escaping the library root via a symlink must be rejected")
        assertFalse(
            Files.exists(outside.resolve("NewAlbum")),
            "the directory materialized outside the library root through the symlink must be cleaned up, not left on disk",
        )
    }

    @Test
    fun `legitimate tagged upload creates its artist-album directory inside the library root`(
        @org.junit.jupiter.api.io.TempDir tmp: Path,
    ) {
        val root = tmp.resolve("music").createDirectories()
        val source = taggedFlac(tmp, albumArtist = "Real Artist", album = "Real Album")
        val ingest = UploadedTrackIngest(writer, root.toString())

        val result = ingest.ingest(source, "track.flac")

        assertEquals(true, result is UploadIngestResult.Ingested, "a legitimate upload must still be ingested, got $result")
        assertEquals(true, Files.isDirectory(root.resolve("Real Artist").resolve("Real Album")), "the artist/album directory must exist inside root")
    }
}
