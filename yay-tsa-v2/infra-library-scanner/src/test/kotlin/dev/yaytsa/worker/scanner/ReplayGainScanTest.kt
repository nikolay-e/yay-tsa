package dev.yaytsa.worker.scanner

import dev.yaytsa.persistence.library.repository.AudioTrackRepository
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.flac.FlacTag
import org.junit.jupiter.api.Assertions.assertEquals
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
import java.nio.file.attribute.FileTime
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
    ],
)
class ReplayGainScanTest {
    @Autowired lateinit var writer: LibraryWriter

    @Autowired lateinit var trackRepo: AudioTrackRepository

    @Autowired lateinit var jdbc: JdbcTemplate

    companion object {
        @JvmStatic
        private val postgres: PostgreSQLContainer<*> =
            PostgreSQLContainer("pgvector/pgvector:pg16")
                .withDatabaseName("replaygain_test")
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
        val coverCacheDir: Path = Files.createTempDirectory("replaygain-cover-cache")

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

    private fun bumpMtime(file: Path) {
        Files.setLastModifiedTime(file, FileTime.from(Files.getLastModifiedTime(file).toInstant().plusSeconds(60)))
    }

    private fun assertDecimalEquals(
        expected: String,
        actual: BigDecimal?,
        message: String,
    ) {
        assertEquals(0, BigDecimal(expected).compareTo(actual), "$message, was $actual")
    }

    @Test
    fun `replaygain vorbis comments populate the audio_tracks gain and peak columns`(
        @TempDir root: Path,
    ) {
        val file = place(root, "Slipknot/Iowa/01 - People = Shit.flac")
        writeVorbisFields(
            file,
            mapOf(
                "REPLAYGAIN_TRACK_GAIN" to "-6.50 dB",
                "REPLAYGAIN_ALBUM_GAIN" to "+2.35 dB",
                "REPLAYGAIN_TRACK_PEAK" to "0.988547",
            ),
        )

        val id = writer.upsertTrack(root, file)

        val track = trackRepo.findById(id!!).get()
        assertDecimalEquals("-6.5", track.replaygainTrackGain, "track gain must parse '-6.50 dB'")
        assertDecimalEquals("2.35", track.replaygainAlbumGain, "album gain must parse '+2.35 dB'")
        assertDecimalEquals("0.988547", track.replaygainTrackPeak, "track peak must parse '0.988547'")
    }

    @Test
    fun `r128 q7_8 gain converts to replaygain decibels relative to the -18 LUFS reference`(
        @TempDir root: Path,
    ) {
        val file = place(root, "Opus Artist/Album/01 - Loud.flac")
        writeVorbisFields(
            file,
            mapOf(
                "R128_TRACK_GAIN" to "-1536",
                "R128_ALBUM_GAIN" to "512",
            ),
        )

        val id = writer.upsertTrack(root, file)

        val track = trackRepo.findById(id!!).get()
        assertDecimalEquals("-1", track.replaygainTrackGain, "R128 -1536/256 + 5 must yield -1 dB")
        assertDecimalEquals("7", track.replaygainAlbumGain, "R128 512/256 + 5 must yield 7 dB")
        assertNull(track.replaygainTrackPeak, "R128 tags carry no peak")
    }

    @Test
    fun `unparsable and out-of-range gain values are stored as null`(
        @TempDir root: Path,
    ) {
        val file = place(root, "Broken/Tags/01 - Junk.flac")
        writeVorbisFields(
            file,
            mapOf(
                "REPLAYGAIN_TRACK_GAIN" to "loud",
                "REPLAYGAIN_ALBUM_GAIN" to "-1234.5 dB",
                "REPLAYGAIN_TRACK_PEAK" to "-0.5",
            ),
        )

        val id = writer.upsertTrack(root, file)

        val track = trackRepo.findById(id!!).get()
        assertNull(track.replaygainTrackGain, "non-numeric gain must not be stored")
        assertNull(track.replaygainAlbumGain, "a gain beyond +-60 dB is tagger garbage and must not be stored")
        assertNull(track.replaygainTrackPeak, "a negative peak must not be stored")
    }

    @Test
    fun `retagged file updates gain values on rescan and comma decimals parse`(
        @TempDir root: Path,
    ) {
        val file = place(root, "Kino/Gruppa Krovi/01 - Gruppa Krovi.flac")
        writeVorbisFields(file, mapOf("REPLAYGAIN_TRACK_GAIN" to "-3.20 dB"))
        val id = writer.upsertTrack(root, file)
        assertDecimalEquals("-3.2", trackRepo.findById(id!!).get().replaygainTrackGain, "initial scan must store the tag value")

        writeVorbisFields(file, mapOf("REPLAYGAIN_TRACK_GAIN" to "-8,75 dB"))
        bumpMtime(file)
        val idAfterRescan = writer.upsertTrack(root, file)

        assertEquals(id, idAfterRescan, "rescan must refresh the existing row")
        assertDecimalEquals(
            "-8.75",
            trackRepo.findById(id).get().replaygainTrackGain,
            "changed file must overwrite the stored gain, comma decimal included",
        )
    }
}
