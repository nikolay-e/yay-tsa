package dev.yaytsa.persistence.library

import dev.yaytsa.application.library.port.LibraryQueryPort
import dev.yaytsa.domain.library.EntityType
import dev.yaytsa.persistence.library.entity.AlbumJpa
import dev.yaytsa.persistence.library.entity.ArtistJpa
import dev.yaytsa.persistence.library.entity.AudioTrackJpa
import dev.yaytsa.persistence.library.entity.EntityGenreJpa
import dev.yaytsa.persistence.library.entity.GenreJpa
import dev.yaytsa.persistence.library.entity.ImageJpa
import dev.yaytsa.persistence.library.entity.LibraryEntityJpa
import dev.yaytsa.persistence.library.repository.AlbumRepository
import dev.yaytsa.persistence.library.repository.ArtistRepository
import dev.yaytsa.persistence.library.repository.AudioTrackRepository
import dev.yaytsa.persistence.library.repository.EntityGenreRepository
import dev.yaytsa.persistence.library.repository.GenreRepository
import dev.yaytsa.persistence.library.repository.ImageRepository
import dev.yaytsa.persistence.library.repository.LibraryEntityRepository
import dev.yaytsa.shared.EntityId
import dev.yaytsa.shared.TrackId
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.time.LocalDate
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JpaLibraryQueryPortTest : LibraryPersistenceTestBase() {
    @Autowired
    lateinit var port: LibraryQueryPort

    @Autowired
    lateinit var entityRepo: LibraryEntityRepository

    @Autowired
    lateinit var artistRepo: ArtistRepository

    @Autowired
    lateinit var albumRepo: AlbumRepository

    @Autowired
    lateinit var trackRepo: AudioTrackRepository

    @Autowired
    lateinit var genreRepo: GenreRepository

    @Autowired
    lateinit var entityGenreRepo: EntityGenreRepository

    @Autowired
    lateinit var imageRepo: ImageRepository

    private val artistId = UUID.randomUUID()
    private val albumId = UUID.randomUUID()
    private val track1Id = UUID.randomUUID()
    private val track2Id = UUID.randomUUID()
    private val genreId = UUID.randomUUID()
    private val imageId = UUID.randomUUID()

    @BeforeEach
    fun setUp() {
        entityGenreRepo.deleteAll()
        imageRepo.deleteAll()
        trackRepo.deleteAll()
        albumRepo.deleteAll()
        artistRepo.deleteAll()
        genreRepo.deleteAll()
        entityRepo.deleteAll()

        // Artist entity
        entityRepo.save(
            LibraryEntityJpa(
                id = artistId,
                entityType = EntityType.ARTIST.name,
                name = "Pink Floyd",
                sortName = "Pink Floyd",
                searchText = "pink floyd",
            ),
        )
        artistRepo.save(
            ArtistJpa(
                entityId = artistId,
                musicbrainzId = "83d91898-7763-47d7-b03b-b92132375c47",
                biography = "English rock band",
            ),
        )

        // Album entity
        entityRepo.save(
            LibraryEntityJpa(
                id = albumId,
                entityType = EntityType.ALBUM.name,
                name = "The Dark Side of the Moon",
                sortName = "Dark Side of the Moon",
                searchText = "dark side moon",
            ),
        )
        albumRepo.save(
            AlbumJpa(
                entityId = albumId,
                artistId = artistId,
                releaseDate = LocalDate.of(1973, 3, 1),
                totalTracks = 2,
                totalDiscs = 1,
            ),
        )

        // Track entities
        entityRepo.save(
            LibraryEntityJpa(
                id = track1Id,
                entityType = EntityType.TRACK.name,
                name = "Speak to Me",
                sortName = "Speak to Me",
                searchText = "speak to me",
            ),
        )
        trackRepo.save(
            AudioTrackJpa(
                entityId = track1Id,
                albumId = albumId,
                albumArtistId = artistId,
                trackNumber = 1,
                discNumber = 1,
                durationMs = 68000,
                bitrate = 320,
                sampleRate = 44100,
                channels = 2,
                year = 1973,
                codec = "FLAC",
            ),
        )

        entityRepo.save(
            LibraryEntityJpa(
                id = track2Id,
                entityType = EntityType.TRACK.name,
                name = "Breathe",
                sortName = "Breathe",
                searchText = "breathe",
            ),
        )
        trackRepo.save(
            AudioTrackJpa(
                entityId = track2Id,
                albumId = albumId,
                albumArtistId = artistId,
                trackNumber = 2,
                discNumber = 1,
                durationMs = 169000,
                bitrate = 320,
                sampleRate = 44100,
                channels = 2,
                year = 1973,
                codec = "FLAC",
            ),
        )

        // Genre
        genreRepo.save(GenreJpa(id = genreId, name = "Progressive Rock"))
        entityGenreRepo.save(EntityGenreJpa(entityId = track1Id, genreId = genreId))
        entityGenreRepo.save(EntityGenreJpa(entityId = track2Id, genreId = genreId))

        // Image
        imageRepo.save(
            ImageJpa(
                id = imageId,
                entityId = albumId,
                imageType = "COVER",
                path = "/covers/dsotm.jpg",
                isPrimary = true,
            ),
        )
    }

    @Test
    fun `getArtist returns mapped artist`() {
        val artist = port.getArtist(EntityId(artistId.toString()))
        assertNotNull(artist)
        assertEquals("Pink Floyd", artist.name)
        assertEquals("83d91898-7763-47d7-b03b-b92132375c47", artist.musicbrainzId)
        assertEquals("English rock band", artist.biography)
    }

    @Test
    fun `getArtist returns null for unknown id`() {
        assertNull(port.getArtist(EntityId(UUID.randomUUID().toString())))
    }

    @Test
    fun `getAlbum returns mapped album with cover`() {
        val album = port.getAlbum(EntityId(albumId.toString()))
        assertNotNull(album)
        assertEquals("The Dark Side of the Moon", album.name)
        assertEquals(EntityId(artistId.toString()), album.artistId)
        assertEquals(LocalDate.of(1973, 3, 1), album.releaseDate)
        assertEquals("/covers/dsotm.jpg", album.coverImagePath)
    }

    @Test
    fun `getTrack returns mapped track with genre`() {
        val track = port.getTrack(EntityId(track1Id.toString()))
        assertNotNull(track)
        assertEquals("Speak to Me", track.name)
        assertEquals(1, track.trackNumber)
        assertEquals(68000L, track.durationMs)
        assertEquals("FLAC", track.codec)
        assertEquals("Progressive Rock", track.genre)
    }

    @Test
    fun `browseArtists returns paginated list`() {
        val artists = port.browseArtists(limit = 10, offset = 0)
        assertEquals(1, artists.size)
        assertEquals("Pink Floyd", artists[0].name)
    }

    @Test
    fun `browseAlbumsByArtist returns albums for artist`() {
        val albums = port.browseAlbumsByArtist(EntityId(artistId.toString()))
        assertEquals(1, albums.size)
        assertEquals("The Dark Side of the Moon", albums[0].name)
    }

    @Test
    fun `browseTracksByAlbum returns tracks ordered by disc and track number`() {
        val tracks = port.browseTracksByAlbum(EntityId(albumId.toString()))
        assertEquals(2, tracks.size)
        assertEquals("Speak to Me", tracks[0].name)
        assertEquals("Breathe", tracks[1].name)
    }

    @Test
    fun `searchText finds entities by name`() {
        val results = port.searchText("Pink", limit = 10, offset = 0)
        assertEquals(1, results.artists.size)
        assertEquals("Pink Floyd", results.artists[0].name)
        assertTrue(results.albums.isEmpty())
        assertTrue(results.tracks.isEmpty())
    }

    @Test
    fun `searchText finds tracks`() {
        val results = port.searchText("Breathe", limit = 10, offset = 0)
        assertEquals(1, results.tracks.size)
        assertEquals("Breathe", results.tracks[0].name)
    }

    @Test
    fun `trackIdsExist returns only existing track ids`() {
        val existing = port.trackIdsExist(setOf(TrackId(track1Id.toString()), TrackId(UUID.randomUUID().toString())))
        assertEquals(1, existing.size)
        assertTrue(existing.contains(TrackId(track1Id.toString())))
    }

    @Test
    fun `getGenres returns genres for entity`() {
        val genres = port.getGenres(EntityId(track1Id.toString()))
        assertEquals(1, genres.size)
        assertEquals("Progressive Rock", genres[0].name)
    }

    @Test
    fun `getPrimaryImage returns primary image`() {
        val image = port.getPrimaryImage(EntityId(albumId.toString()))
        assertNotNull(image)
        assertEquals("/covers/dsotm.jpg", image.path)
        assertTrue(image.isPrimary)
    }

    @Test
    fun `getPrimaryImage returns null when no primary image`() {
        assertNull(port.getPrimaryImage(EntityId(artistId.toString())))
    }
}
