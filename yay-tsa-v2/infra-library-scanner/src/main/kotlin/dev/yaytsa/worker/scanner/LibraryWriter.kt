package dev.yaytsa.worker.scanner

import dev.yaytsa.application.shared.port.Clock
import dev.yaytsa.persistence.library.entity.AlbumJpa
import dev.yaytsa.persistence.library.entity.ArtistJpa
import dev.yaytsa.persistence.library.entity.AudioTrackJpa
import dev.yaytsa.persistence.library.entity.EntityGenreJpa
import dev.yaytsa.persistence.library.entity.LibraryEntityJpa
import dev.yaytsa.persistence.library.repository.AlbumRepository
import dev.yaytsa.persistence.library.repository.ArtistRepository
import dev.yaytsa.persistence.library.repository.AudioTrackRepository
import dev.yaytsa.persistence.library.repository.EntityGenreRepository
import dev.yaytsa.persistence.library.repository.GenreRepository
import dev.yaytsa.persistence.library.repository.LibraryEntityRepository
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.nio.file.Files
import java.nio.file.Path
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import kotlin.io.path.extension
import kotlin.io.path.nameWithoutExtension

@Component
class LibraryWriter(
    private val entityRepo: LibraryEntityRepository,
    private val trackRepo: AudioTrackRepository,
    private val artistRepo: ArtistRepository,
    private val albumRepo: AlbumRepository,
    private val genreRepo: GenreRepository,
    private val entityGenreRepo: EntityGenreRepository,
    private val clock: Clock,
) {
    @Transactional
    fun upsertTrack(
        root: Path,
        file: Path,
    ) {
        val relativePath = root.relativize(file).toString()
        val codec = file.extension.lowercase()
        val sizeBytes = Files.size(file)
        val mtime = OffsetDateTime.ofInstant(Files.getLastModifiedTime(file).toInstant(), ZoneOffset.UTC)

        // Use sourcePath as the natural key — it has a unique constraint
        val existing = entityRepo.findBySourcePath(relativePath)
        if (existing != null) {
            // Already indexed — skip (future: compare mtime for re-scan)
            return
        }

        // Read audio tags
        val audioFile =
            try {
                AudioFileIO.read(file.toFile())
            } catch (_: Exception) {
                null
            }

        val audioHeader = audioFile?.audioHeader
        val audioTag = audioFile?.tag

        val trackName =
            audioTag
                ?.getFirst(FieldKey.TITLE)
                ?.takeIf { it.isNotBlank() }
                ?: file.nameWithoutExtension
        val artistName = audioTag?.getFirst(FieldKey.ARTIST)?.takeIf { it.isNotBlank() }
        val albumName = audioTag?.getFirst(FieldKey.ALBUM)?.takeIf { it.isNotBlank() }
        val trackNumber = audioTag?.getFirst(FieldKey.TRACK)?.toIntOrNull()
        val discNumber = audioTag?.getFirst(FieldKey.DISC_NO)?.toIntOrNull() ?: 1
        val year = audioTag?.getFirst(FieldKey.YEAR)?.toIntOrNull()
        val genre = audioTag?.getFirst(FieldKey.GENRE)?.takeIf { it.isNotBlank() }
        val durationMs = audioHeader?.trackLength?.let { it * 1000L }
        val bitrate = audioHeader?.bitRateAsNumber?.toInt()
        val sampleRate = audioHeader?.sampleRateAsNumber?.toInt()
        val channels = audioHeader?.channels?.toIntOrNull()

        val now = OffsetDateTime.ofInstant(clock.now(), ZoneOffset.UTC)

        // Upsert artist
        val artistId =
            if (artistName != null) {
                val artistSourceKey = "artist:${artistName.lowercase()}"
                val existingArtist = entityRepo.findBySourcePath(artistSourceKey)
                existingArtist?.id ?: run {
                    val id = UUID.randomUUID()
                    entityRepo.save(
                        LibraryEntityJpa(
                            id = id,
                            entityType = "ARTIST",
                            name = artistName,
                            sortName = artistName.lowercase(),
                            sourcePath = artistSourceKey,
                            searchText = artistName.lowercase(),
                            createdAt = now,
                            updatedAt = now,
                        ),
                    )
                    artistRepo.save(ArtistJpa(entityId = id))
                    id
                }
            } else {
                null
            }

        // Upsert album
        val albumId =
            if (albumName != null) {
                val albumSourceKey = "album:${artistName?.lowercase() ?: "unknown"}:${albumName.lowercase()}"
                val existingAlbum = entityRepo.findBySourcePath(albumSourceKey)
                existingAlbum?.id ?: run {
                    val id = UUID.randomUUID()
                    entityRepo.save(
                        LibraryEntityJpa(
                            id = id,
                            entityType = "ALBUM",
                            name = albumName,
                            sortName = albumName.lowercase(),
                            parentId = artistId,
                            sourcePath = albumSourceKey,
                            searchText = albumName.lowercase(),
                            createdAt = now,
                            updatedAt = now,
                        ),
                    )
                    albumRepo.save(
                        AlbumJpa(
                            entityId = id,
                            artistId = artistId,
                        ),
                    )
                    id
                }
            } else {
                null
            }

        // Save track entity
        val entityId = UUID.randomUUID()
        val searchText =
            listOfNotNull(trackName, artistName, albumName)
                .joinToString(" ") { it.lowercase() }

        entityRepo.save(
            LibraryEntityJpa(
                id = entityId,
                entityType = "TRACK",
                name = trackName,
                sortName = trackName.lowercase(),
                parentId = albumId,
                sourcePath = relativePath,
                container = codec,
                sizeBytes = sizeBytes,
                mtime = mtime,
                libraryRoot = root.toString(),
                searchText = searchText,
                createdAt = now,
                updatedAt = now,
            ),
        )

        trackRepo.save(
            AudioTrackJpa(
                entityId = entityId,
                albumId = albumId,
                albumArtistId = artistId,
                trackNumber = trackNumber,
                discNumber = discNumber,
                durationMs = durationMs,
                bitrate = bitrate,
                sampleRate = sampleRate,
                channels = channels,
                year = year,
                codec = codec,
            ),
        )

        // Upsert genre and link to track
        if (genre != null) {
            val candidateId = UUID.randomUUID()
            genreRepo.upsertByName(id = candidateId, name = genre)
            val genreId = genreRepo.findByName(genre)?.id ?: candidateId
            entityGenreRepo.save(EntityGenreJpa(entityId = entityId, genreId = genreId))
        }
    }
}
