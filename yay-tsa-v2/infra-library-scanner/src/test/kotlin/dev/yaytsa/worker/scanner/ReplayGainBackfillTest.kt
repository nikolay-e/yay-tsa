package dev.yaytsa.worker.scanner

import dev.yaytsa.persistence.library.repository.AudioTrackRepository
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.flac.FlacTag
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.TestPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.io.path.createDirectories

@SpringBootTest(classes = [ScannerTestApplication::class])
@TestPropertySource(
    properties = [
        "spring.flyway.enabled=true",
        "spring.flyway.locations=classpath:db/library",
        "spring.flyway.schemas=core_v2_library",
        "spring.flyway.default-schema=core_v2_library",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.jpa.properties.hibernate.default_schema=core_v2_library",
        "yaytsa.scanner.replaygain-backfill-on-startup=false",
    ],
)
class ReplayGainBackfillTest {
    @Autowired lateinit var writer: LibraryWriter

    @Autowired lateinit var backfill: ReplayGainBackfill

    @Autowired lateinit var trackRepo: AudioTrackRepository

    @Autowired lateinit var jdbc: JdbcTemplate

    companion object {
        @JvmStatic
        private val postgres: PostgreSQLContainer<*> =
            PostgreSQLContainer("pgvector/pgvector:pg16")
                .withDatabaseName("replaygain_backfill_test")
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
        val coverCacheDir: Path = Files.createTempDirectory("replaygain-backfill-cover-cache")

        @JvmStatic
        @DynamicPropertySource
        fun props(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { postgres.jdbcUrl + "&stringtype=unspecified" }
            registry.add("spring.datasource.username") { postgres.username }
            registry.add("spring.datasource.password") { postgres.password }
            registry.add("yaytsa.image.cover-cache-dir") { coverCacheDir.toString() }
        }
    }

    @BeforeEach
    fun clean() {
        jdbc.execute("TRUNCATE TABLE core_v2_library.entities CASCADE")
    }

    private fun place(
        root: Path,
        relative: String,
    ): Path {
        val target = root.resolve(relative)
        target.parent.createDirectories()
        javaClass.getResourceAsStream("/fixtures/silent-3s.flac").use { input ->
            Files.copy(requireNotNull(input) { "fixture silent-3s.flac missing" }, target)
        }
        return target
    }

    private fun writeVorbisFields(
        file: Path,
        fields: Map<String, String>,
    ) {
        val audioFile = AudioFileIO.read(file.toFile())
        val tag = audioFile.tagOrCreateAndSetDefault as FlacTag
        fields.forEach { (key, value) -> tag.setField(tag.createField(key, value)) }
        AudioFileIO.write(audioFile)
    }

    private fun seedLegacyRow(
        root: Path,
        relative: String,
    ): UUID {
        val file = place(root, relative)
        val id = requireNotNull(writer.upsertTrack(root, file)) { "fixture must be ingestible" }
        jdbc.update(
            "UPDATE core_v2_library.audio_tracks SET replaygain_track_gain = NULL, replaygain_album_gain = NULL, " +
                "replaygain_track_peak = NULL, replaygain_checked_at = NULL WHERE entity_id = ?",
            id,
        )
        return id
    }

    private fun assertDecimalEquals(
        expected: String,
        actual: BigDecimal?,
        message: String,
    ) {
        assertEquals(0, BigDecimal(expected).compareTo(actual), "$message, was $actual")
    }

    @Test
    fun `backfill populates gain columns for already-scanned files carrying replaygain tags`(
        @TempDir root: Path,
    ) {
        val id = seedLegacyRow(root, "Slipknot/Iowa/02 - The Blister Exists.flac")
        writeVorbisFields(
            root.resolve("Slipknot/Iowa/02 - The Blister Exists.flac"),
            mapOf(
                "REPLAYGAIN_TRACK_GAIN" to "-7.25 dB",
                "REPLAYGAIN_ALBUM_GAIN" to "+1.10 dB",
                "REPLAYGAIN_TRACK_PEAK" to "0.977001",
            ),
        )

        val summary = backfill.runBackfill()

        assertEquals(1, summary.examined, "legacy row must be picked up")
        assertEquals(1, summary.populated, "tagged file must be populated")
        val track = trackRepo.findById(id).get()
        assertDecimalEquals("-7.25", track.replaygainTrackGain, "track gain must come from the file tag")
        assertDecimalEquals("1.1", track.replaygainAlbumGain, "album gain must come from the file tag")
        assertDecimalEquals("0.977001", track.replaygainTrackPeak, "track peak must come from the file tag")
        assertNotNull(track.replaygainCheckedAt, "populated row must be marked checked")
    }

    @Test
    fun `tagless files are marked checked and never reprocessed`(
        @TempDir root: Path,
    ) {
        val id = seedLegacyRow(root, "Kino/Zvezda/01 - Untagged.flac")

        val firstRun = backfill.runBackfill()

        assertEquals(1, firstRun.examined)
        assertEquals(1, firstRun.taglessMarked, "file without replaygain tags must be marked checked")
        val track = trackRepo.findById(id).get()
        assertNull(track.replaygainTrackGain, "tagless file must keep NULL gains")
        assertNotNull(track.replaygainCheckedAt, "tagless file must be marked checked")

        val secondRun = backfill.runBackfill()
        assertEquals(0, secondRun.examined, "checked rows must never be re-read")
    }

    @Test
    fun `rows whose file vanished stay unchecked for a later run`(
        @TempDir root: Path,
    ) {
        val id = seedLegacyRow(root, "Opus Artist/Album/01 - Gone.flac")
        Files.delete(root.resolve("Opus Artist/Album/01 - Gone.flac"))

        val summary = backfill.runBackfill()

        assertEquals(1, summary.examined)
        assertEquals(1, summary.skippedMissingFile, "missing file must be skipped, not marked")
        val track = trackRepo.findById(id).get()
        assertNull(track.replaygainCheckedAt, "missing file must stay unchecked so a later run can retry")
    }

    @Test
    fun `fresh scans stamp checked-at so new files never enter the backfill`(
        @TempDir root: Path,
    ) {
        val file = place(root, "Fresh/Scan/01 - New.flac")
        val id = requireNotNull(writer.upsertTrack(root, file))

        assertNotNull(trackRepo.findById(id).get().replaygainCheckedAt, "upsert must mark the row checked")
        assertEquals(0, backfill.runBackfill().examined, "freshly scanned rows must not be backfill candidates")
    }
}
