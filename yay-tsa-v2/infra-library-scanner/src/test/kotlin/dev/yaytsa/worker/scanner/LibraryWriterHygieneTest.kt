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
        val coverCacheDir: Path = Files.createTempDirectory("scanner-cover-cache")

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

    private val onePixelPng: ByteArray =
        java.util.Base64
            .getDecoder()
            .decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAIAAACQd1PeAAAADElEQVR4nGP4z8AAAAMBAQDJ/pLvAAAAAElFTkSuQmCC")

    private fun setEmbeddedArtwork(file: Path) {
        val af = AudioFileIO.read(file.toFile())
        val tag = af.tagOrCreateAndSetDefault
        val artwork =
            org.jaudiotagger.tag.images.ArtworkFactory
                .getNew()
        artwork.binaryData = onePixelPng
        artwork.mimeType = "image/png"
        artwork.pictureType = 3
        artwork.setImageFromData()
        tag.setField(artwork)
        AudioFileIO.write(af)
    }

    @Test
    fun `music track with embedded art and no folder cover materializes an album-level Primary image`(
        @org.junit.jupiter.api.io.TempDir root: Path,
    ) {
        val file = place(root, "silent-3s.flac", "Pink Floyd/Animals/01 - Pigs.flac")
        setEmbeddedArtwork(file)

        writer.upsertTrack(root, file)

        val album = entityRepo.findByEntityTypeOrderBySortName("ALBUM").firstOrNull()
        assertTrue(album != null, "album entity must be created")
        val cover = imageRepo.findByEntityIdAndIsPrimaryTrue(album!!.id)
        assertTrue(cover != null, "album with only embedded art must get a materialized Primary image")
        assertTrue(cover!!.path.contains("scanner-cover-cache"), "materialized cover must live in the cover cache, was ${cover.path}")
        assertTrue(Files.isRegularFile(Path.of(cover.path)), "the cached cover bytes must exist on disk")
    }

    @Test
    fun `audiobook track with embedded art gets a track-level Primary image so imageTags is advertised`(
        @org.junit.jupiter.api.io.TempDir root: Path,
    ) {
        val file = place(root, "silent-3s.flac", "Sektor Gaza/Kashchey/01 - Chapter One.flac")
        val af = AudioFileIO.read(file.toFile())
        af.tagOrCreateAndSetDefault.setField(FieldKey.GENRE, "Audiobook")
        AudioFileIO.write(af)
        setEmbeddedArtwork(file)

        val trackId = writer.upsertTrack(root, file)

        assertTrue(trackId != null, "audiobook track must be inserted")
        val cover = imageRepo.findByEntityIdAndIsPrimaryTrue(trackId!!)
        assertTrue(cover != null, "audiobook track must get its OWN track-level Primary image (so TrackProjection emits imageTags)")
        assertTrue(cover!!.path.contains("scanner-cover-cache"), "audiobook cover must live in the cover cache, was ${cover.path}")
        assertTrue(Files.isRegularFile(Path.of(cover.path)), "the cached audiobook cover bytes must exist on disk")
    }

    @Test
    fun `rescan of an audiobook track with an existing valid cover does not duplicate the image row`(
        @org.junit.jupiter.api.io.TempDir root: Path,
    ) {
        val file = place(root, "silent-3s.flac", "Sektor Gaza/Kashchey/01 - Chapter One.flac")
        val af = AudioFileIO.read(file.toFile())
        af.tagOrCreateAndSetDefault.setField(FieldKey.GENRE, "Audiobook")
        AudioFileIO.write(af)
        setEmbeddedArtwork(file)

        val trackId = writer.upsertTrack(root, file)!!
        Files.setLastModifiedTime(
            file,
            java.nio.file.attribute.FileTime
                .from(Files.getLastModifiedTime(file).toInstant().plusSeconds(60)),
        )
        writer.upsertTrack(root, file)

        assertEquals(1, imageRepo.findByEntityId(trackId).count { it.isPrimary }, "rescan must not create a second Primary image row")
    }

    @Test
    fun `vanished NULL-library_root ghost rows are swept, present NULL-root rows are kept`(
        @org.junit.jupiter.api.io.TempDir root: Path,
    ) {
        // Legacy ghosts: TRACK rows with library_root = NULL (v1 ETL / pre-column scans) and a
        // RELATIVE source_path. The old prefix-LIKE backfill never matched these (relative path
        // vs absolute root prefix); the OR-NULL candidate query + present-path filter must.
        val gone = seedNullRootTrack("Gone/Album/missing.flac")
        val present = seedNullRootTrack("Kept/Album/here.flac")

        val removed = writer.deleteVanishedTracks(root, setOf("Kept/Album/here.flac"))

        assertEquals(1, removed, "exactly the on-disk-absent ghost is swept")
        assertFalse(entityRepo.findById(gone).isPresent, "vanished NULL-root ghost must be deleted")
        assertTrue(entityRepo.findById(present).isPresent, "a NULL-root row still present on disk must survive")
    }

    private fun seedNullRootTrack(relativeSourcePath: String): java.util.UUID = seedTrack(relativeSourcePath, null)

    private fun seedTrack(
        relativeSourcePath: String,
        libraryRoot: String?,
    ): java.util.UUID {
        val id = java.util.UUID.randomUUID()
        jdbc.update(
            "INSERT INTO core_v2_library.entities (id, entity_type, name, sort_name, source_path, library_root, search_text) " +
                "VALUES (?,?,?,?,?,?,?)",
            id,
            "TRACK",
            "Ghost ${relativeSourcePath.substringAfterLast('/')}",
            "ghost",
            relativeSourcePath,
            libraryRoot,
            "ghost",
        )
        jdbc.update("INSERT INTO core_v2_library.audio_tracks (entity_id, duration_ms) VALUES (?,?)", id, 120000L)
        return id
    }

    @Test
    fun `reconcile refuses to delete anything when the walk yielded zero files`(
        @org.junit.jupiter.api.io.TempDir root: Path,
    ) {
        seedTrack("A/B/one.flac", root.toString())
        seedTrack("A/B/two.flac", root.toString())

        val removed = writer.deleteVanishedTracks(root, emptySet())

        assertEquals(0, removed, "an empty walk (unmounted volume) must never trigger deletes")
        assertEquals(2, trackRepo.count(), "all tracks must survive an empty-walk reconcile")
    }

    @Test
    fun `reconcile refuses to delete more than half the tracks in one sweep`(
        @org.junit.jupiter.api.io.TempDir root: Path,
    ) {
        val paths = (1..4).map { "A/B/track$it.flac" }
        paths.forEach { seedTrack(it, root.toString()) }

        val refused = writer.deleteVanishedTracks(root, setOf(paths[0]))
        assertEquals(0, refused, "deleting 3 of 4 tracks exceeds the 50% threshold and must be refused")
        assertEquals(4, trackRepo.count())

        val removed = writer.deleteVanishedTracks(root, setOf(paths[0], paths[1], paths[2]))
        assertEquals(1, removed, "deleting 1 of 4 tracks is below the threshold and must proceed")
        assertEquals(3, trackRepo.count())
    }

    @Test
    fun `renamed file keeps its entity UUID instead of delete-plus-recreate`(
        @org.junit.jupiter.api.io.TempDir root: Path,
    ) {
        val original = place(root, "silent-3s.flac", "Slipknot/Iowa/01 - Eyeless.flac")
        val id = writer.upsertTrack(root, original)
        val renamed = root.resolve("Slipknot/Iowa (Remaster)/01 - Eyeless.flac")
        renamed.parent.createDirectories()
        Files.move(original, renamed)

        val idAfterRename = writer.upsertTrack(root, renamed)

        assertEquals(id, idAfterRename, "a moved file must be matched by (size, mtime) and keep its UUID")
        assertEquals(1, trackRepo.count(), "rename must not create a second track row")
        assertEquals(
            "Slipknot/Iowa (Remaster)/01 - Eyeless.flac",
            entityRepo.findById(id!!).get().sourcePath,
            "source_path must be rewritten in place",
        )

        val removed = writer.deleteVanishedTracks(root, setOf("Slipknot/Iowa (Remaster)/01 - Eyeless.flac"))
        assertEquals(0, removed, "the renamed track is present and must not be swept")
    }

    @Test
    fun `unchanged file short-circuits on size and mtime without re-reading tags`(
        @org.junit.jupiter.api.io.TempDir root: Path,
    ) {
        val file = place(root, "silent-3s.flac", "Slipknot/Iowa/01 - Eyeless.flac")
        val id = writer.upsertTrack(root, file)
        jdbc.update("UPDATE core_v2_library.entities SET name = 'Stale Name' WHERE id = ?", id)

        val idAfterRescan = writer.upsertTrack(root, file)

        assertEquals(id, idAfterRescan)
        assertEquals(
            "Stale Name",
            entityRepo.findById(id!!).get().name,
            "an unchanged file must skip the metadata-refresh path entirely",
        )
    }

    @Test
    fun `changed mtime triggers a full metadata refresh including genre, year and search_text`(
        @org.junit.jupiter.api.io.TempDir root: Path,
    ) {
        val file = place(root, "silent-3s.flac", "Author/Book/01 - Chapter One.flac")
        val id = writer.upsertTrack(root, file)
        jdbc.update("UPDATE core_v2_library.entities SET name = 'Stale Name', search_text = 'stale' WHERE id = ?", id)

        val af = AudioFileIO.read(file.toFile())
        af.tagOrCreateAndSetDefault.setField(FieldKey.GENRE, "Audiobook")
        af.tag.setField(FieldKey.YEAR, "2008-05-12")
        af.tag.setField(FieldKey.TRACK, "1/12")
        AudioFileIO.write(af)
        Files.setLastModifiedTime(
            file,
            java.nio.file.attribute.FileTime
                .from(Files.getLastModifiedTime(file).toInstant().plusSeconds(60)),
        )

        val idAfterRetag = writer.upsertTrack(root, file)

        assertEquals(id, idAfterRetag, "retagging must refresh the existing row, not create a new one")
        val entity = entityRepo.findById(id!!).get()
        assertEquals("Chapter One", entity.name, "stale title must be rewritten from the changed file")
        assertTrue(entity.searchText!!.contains("chapter one"), "search_text must be recomputed, was ${entity.searchText}")
        val track = trackRepo.findById(id).get()
        assertEquals(2008, track.year, "year '2008-05-12' must parse its leading digit run")
        assertEquals(1, track.trackNumber, "track '1/12' must parse its leading digit run")
        val genreNames =
            jdbc.queryForList(
                "SELECT g.name FROM core_v2_library.genres g " +
                    "JOIN core_v2_library.entity_genres eg ON eg.genre_id = g.id WHERE eg.entity_id = ?",
                String::class.java,
                id,
            )
        assertEquals(listOf("Audiobook"), genreNames, "genre links must be rewritten on refresh")
    }

    @Test
    fun `scan ingests files under a hidden absolute root and skips hidden subdirectories`(
        @org.junit.jupiter.api.io.TempDir tmp: Path,
    ) {
        val root = tmp.resolve(".media").resolve("music").createDirectories()
        place(root, "silent-3s.flac", "Artist/Album/01 - Visible.flac")
        place(root, "silent-3s.flac", ".stversions/Artist/Album/01 - Shadow.flac")

        LibraryScanner(writer, root.toString()).scan()

        assertEquals(listOf("Visible"), trackNames(), "hidden-dir filter must apply relative to the root, not to absolute segments")
    }

    @Test
    fun `one unreadable directory is skipped without aborting the scan`(
        @org.junit.jupiter.api.io.TempDir root: Path,
    ) {
        place(root, "silent-3s.flac", "Good/Album/01 - Reachable.flac")
        val locked = root.resolve("Locked").createDirectories()
        Files.setPosixFilePermissions(locked, emptySet())
        org.junit.jupiter.api.Assumptions
            .assumeFalse(Files.isReadable(locked))
        try {
            LibraryScanner(writer, root.toString()).scan()
        } finally {
            Files.setPosixFilePermissions(
                locked,
                java.nio.file.attribute.PosixFilePermissions
                    .fromString("rwxr-xr-x"),
            )
        }

        assertEquals(listOf("Reachable"), trackNames(), "an AccessDenied subdirectory must not abort the whole scan")
    }

    @Test
    fun `planted symlinks are not followed into outside files`(
        @org.junit.jupiter.api.io.TempDir tmp: Path,
    ) {
        val outsideFile = place(tmp, "silent-3s.flac", "outside/01 - Outside.flac")
        val root = tmp.resolve("music").createDirectories()
        val albumDir = root.resolve("Artist/Album").createDirectories()
        Files.createSymbolicLink(albumDir.resolve("01 - Linked.flac"), outsideFile)
        Files.createSymbolicLink(root.resolve("LinkedDir"), tmp.resolve("outside"))

        LibraryScanner(writer, root.toString()).scan()

        assertEquals(0, trackRepo.count(), "symlinked files and directories must not be pulled into the library")
    }

    @Test
    fun `band names with ampersand comma and slash stay intact, semicolon still splits`(
        @org.junit.jupiter.api.io.TempDir root: Path,
    ) {
        val duo = place(root, "silent-3s.flac", "X/Y/01 - Duo.flac")
        var af = AudioFileIO.read(duo.toFile())
        af.tagOrCreateAndSetDefault.setField(FieldKey.ARTIST, "Simon & Garfunkel")
        AudioFileIO.write(af)
        writer.upsertTrack(root, duo)

        val multi = place(root, "silent-3s.flac", "X/Y/02 - Multi.flac")
        af = AudioFileIO.read(multi.toFile())
        af.tagOrCreateAndSetDefault.setField(FieldKey.ARTIST, "Korn; Slipknot")
        AudioFileIO.write(af)
        writer.upsertTrack(root, multi)

        val artistNames = entityRepo.findByEntityTypeOrderBySortName("ARTIST").map { it.name }
        assertTrue(artistNames.contains("Simon & Garfunkel"), "'&' must not split a band name, got $artistNames")
        assertTrue(artistNames.contains("Korn"), "';' must still split multi-artist tags, got $artistNames")
        assertFalse(artistNames.contains("Simon"), "'Simon & Garfunkel' must not be truncated to 'Simon'")
    }

    @Test
    fun `karaoke unprocessed query returns only non-terminal tracks bounded by limit`(
        @org.junit.jupiter.api.io.TempDir root: Path,
    ) {
        jdbc.execute("CREATE SCHEMA IF NOT EXISTS core_v2_karaoke")
        jdbc.execute(
            "CREATE TABLE IF NOT EXISTS core_v2_karaoke.assets (" +
                "track_id UUID PRIMARY KEY, instrumental_path TEXT, vocal_path TEXT, lyrics_timing TEXT, " +
                "ready_at TIMESTAMPTZ, fail_count INTEGER NOT NULL DEFAULT 0, last_failed_at TIMESTAMPTZ, last_error TEXT)",
        )
        jdbc.execute("TRUNCATE core_v2_karaoke.assets")
        val fresh = seedTrack("K/A/fresh.flac", root.toString())
        val ready = seedTrack("K/A/ready.flac", root.toString())
        val exhausted = seedTrack("K/A/failed.flac", root.toString())
        val retryable = seedTrack("K/A/retry.flac", root.toString())
        jdbc.update("INSERT INTO core_v2_karaoke.assets (track_id, ready_at) VALUES (?, now())", ready)
        jdbc.update("INSERT INTO core_v2_karaoke.assets (track_id, fail_count) VALUES (?, 3)", exhausted)
        jdbc.update("INSERT INTO core_v2_karaoke.assets (track_id, fail_count) VALUES (?, 1)", retryable)

        val unprocessed = entityRepo.findKaraokeUnprocessedTrackIds(3, 50)

        assertEquals(setOf(fresh, retryable), unprocessed.toSet(), "ready and retry-exhausted tracks must be excluded")
        assertEquals(1, entityRepo.findKaraokeUnprocessedTrackIds(3, 1).size, "limit must bound the batch")
    }
}
