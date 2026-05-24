package dev.yaytsa.worker.scanner

import dev.yaytsa.persistence.library.repository.AudioTrackRepository
import dev.yaytsa.persistence.library.repository.ImageRepository
import dev.yaytsa.persistence.library.repository.LibraryEntityRepository
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
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
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories

// Uses its OWN container (not the shared reused one): infra-library-scanner and
// infra-persistence:library both write core_v2_library, and Gradle runs their test
// tasks in parallel — sharing one schema across both would corrupt each other.
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
class LibraryWriterHygieneTest {
    @Autowired lateinit var writer: LibraryWriter

    @Autowired lateinit var entityRepo: LibraryEntityRepository

    @Autowired lateinit var trackRepo: AudioTrackRepository

    @Autowired lateinit var jdbc: JdbcTemplate

    @Autowired lateinit var imageRepo: ImageRepository

    companion object {
        @JvmStatic
        private val postgres: PostgreSQLContainer<*> =
            PostgreSQLContainer("pgvector/pgvector:pg16")
                .withDatabaseName("scanner_test")
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
    }

    private fun place(
        root: Path,
        fixture: String,
        relative: String,
    ): Path {
        val target = root.resolve(relative)
        target.parent.createDirectories()
        javaClass.getResourceAsStream("/fixtures/$fixture").use { input ->
            Files.copy(requireNotNull(input) { "fixture $fixture missing" }, target)
        }
        return target
    }

    private fun trackNames(): List<String> = entityRepo.findByEntityTypeOrderBySortName("TRACK").map { it.name ?: "" }

    @Test
    fun `dash-only and N-A placeholder tags fall back to filename, not the junk string`(
        @org.junit.jupiter.api.io.TempDir root: Path,
    ) {
        val file = place(root, "silent-3s.flac", "The Beatles/Abbey Road/05 - Something.flac")
        val af = AudioFileIO.read(file.toFile())
        af.tagOrCreateAndSetDefault.setField(FieldKey.TITLE, "- -")
        af.tag.setField(FieldKey.ARTIST, "--- ---")
        af.tag.setField(FieldKey.ALBUM, "N/A")
        AudioFileIO.write(af)

        writer.upsertTrack(root, file)

        assertEquals(listOf("Something"), trackNames(), "dash/N-A junk title must fall back to the filename body")
        assertFalse(
            entityRepo.findAll().any { (it.name ?: "").matches(Regex("^[-#?\\s]+$")) || it.name.equals("N/A", ignoreCase = true) },
            "no entity may carry a placeholder-junk name",
        )
    }

    @Test
    fun `tracks shorter than 2s are skipped (Fixes #217)`(
        @org.junit.jupiter.api.io.TempDir root: Path,
    ) {
        val file = place(root, "silent-1s.flac", "Various/Pregaps/00 - pregap.flac")
        writer.upsertTrack(root, file)
        assertEquals(0, trackRepo.count(), "a sub-2s file must not be inserted as a playable track")
    }

    @Test
    fun `filename track-number prefix is stripped when the file has no title tag`(
        @org.junit.jupiter.api.io.TempDir root: Path,
    ) {
        val file = place(root, "silent-3s.flac", "Slipknot/Iowa/01 - Eyeless.flac")
        writer.upsertTrack(root, file)
        assertTrue(trackNames().contains("Eyeless"), "no-title file should store the stripped filename body, got ${trackNames()}")
    }

    @Test
    fun `multi-disc album cover at the album root resolves from a track in a CD subfolder`(
        @org.junit.jupiter.api.io.TempDir root: Path,
    ) {
        // Cover lives only at the album root; the track is one level deeper under CD1.
        val track = place(root, "silent-3s.flac", "Hulkoff/2020 - Pansarfolk/CD1/01 - Pansarfolk.flac")
        val albumRootCover = track.parent.parent.resolve("cover.jpg")
        Files.write(albumRootCover, byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xD9.toByte()))

        writer.upsertTrack(root, track)

        val album = entityRepo.findByEntityTypeOrderBySortName("ALBUM").firstOrNull()
        assertTrue(album != null, "album entity must be created")
        val cover = imageRepo.findByEntityIdAndIsPrimaryTrue(album!!.id)
        assertTrue(cover != null, "multi-disc album must get a Primary cover from the album root")
        assertTrue(cover!!.path.endsWith("2020 - Pansarfolk/cover.jpg"), "cover must resolve to the album-root file, was ${cover.path}")
    }
}
