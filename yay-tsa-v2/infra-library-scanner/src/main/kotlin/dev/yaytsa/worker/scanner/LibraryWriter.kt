package dev.yaytsa.worker.scanner

import dev.yaytsa.application.shared.port.Clock
import dev.yaytsa.persistence.library.entity.AlbumJpa
import dev.yaytsa.persistence.library.entity.ArtistJpa
import dev.yaytsa.persistence.library.entity.AudioTrackJpa
import dev.yaytsa.persistence.library.entity.EntityGenreJpa
import dev.yaytsa.persistence.library.entity.ImageJpa
import dev.yaytsa.persistence.library.entity.LibraryEntityJpa
import dev.yaytsa.persistence.library.repository.AlbumRepository
import dev.yaytsa.persistence.library.repository.ArtistRepository
import dev.yaytsa.persistence.library.repository.AudioTrackRepository
import dev.yaytsa.persistence.library.repository.EntityGenreRepository
import dev.yaytsa.persistence.library.repository.GenreRepository
import dev.yaytsa.persistence.library.repository.ImageRepository
import dev.yaytsa.persistence.library.repository.LibraryEntityRepository
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import org.jaudiotagger.tag.Tag
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
    private val imageRepo: ImageRepository,
    private val clock: Clock,
) {
    private val coverFilenames =
        listOf(
            "cover.jpg",
            "cover.jpeg",
            "cover.png",
            "cover.webp",
            "folder.jpg",
            "folder.jpeg",
            "folder.png",
            "folder.webp",
            "front.jpg",
            "front.jpeg",
            "front.png",
            "front.webp",
            "album.jpg",
            "album.jpeg",
            "album.png",
            "album.webp",
        )

    @Transactional
    fun upsertTrack(
        root: Path,
        file: Path,
    ) {
        val relativePath = root.relativize(file).toString()
        val codec = file.extension.lowercase()
        val sizeBytes = Files.size(file)
        val mtime = OffsetDateTime.ofInstant(Files.getLastModifiedTime(file).toInstant(), ZoneOffset.UTC)

        val audioFile =
            try {
                AudioFileIO.read(file.toFile())
            } catch (_: Exception) {
                null
            }

        val audioHeader = audioFile?.audioHeader
        val audioTag = audioFile?.tag

        val trackName =
            audioTag?.safeGetFirst(FieldKey.TITLE)?.takeIf { it.isNotBlank() }
                ?: file.nameWithoutExtension
        val tagAlbumArtist = audioTag?.safeGetFirst(FieldKey.ALBUM_ARTIST)?.takeIf { it.isNotBlank() }
        val tagArtist = audioTag?.safeGetFirst(FieldKey.ARTIST)?.takeIf { it.isNotBlank() }
        val tagAlbum = audioTag?.safeGetFirst(FieldKey.ALBUM)?.takeIf { it.isNotBlank() }

        val pathSegments =
            root
                .relativize(file)
                .iterator()
                .asSequence()
                .map { it.toString() }
                .toList()
        val folderArtist = pathSegments.getOrNull(0)?.takeIf { pathSegments.size >= 3 && it.isNotBlank() }
        val folderAlbum =
            pathSegments
                .getOrNull(1)
                ?.takeIf { pathSegments.size >= 3 && it.isNotBlank() }
                ?.let { stripLeadingYear(it) }

        val artistName = tagAlbumArtist ?: tagArtist ?: folderArtist
        val albumName = tagAlbum ?: folderAlbum

        val trackNumber = audioTag?.safeGetFirst(FieldKey.TRACK)?.toIntOrNull()
        val discNumber = audioTag?.safeGetFirst(FieldKey.DISC_NO)?.toIntOrNull() ?: 1
        val year = audioTag?.safeGetFirst(FieldKey.YEAR)?.toIntOrNull()
        val genre = audioTag?.safeGetFirst(FieldKey.GENRE)?.takeIf { it.isNotBlank() }
        val durationMs = audioHeader?.trackLength?.let { it * 1000L }
        val bitrate = audioHeader?.bitRateAsNumber?.toInt()
        val sampleRate = audioHeader?.sampleRateAsNumber?.toInt()
        val channels = audioHeader?.channels?.toIntOrNull()

        val now = OffsetDateTime.ofInstant(clock.now(), ZoneOffset.UTC)

        val artistId = artistName?.let { ensureArtist(it, now) }
        val albumId = albumName?.let { ensureAlbum(it, artistName, artistId, now) }

        if (albumId != null) {
            ensureAlbumCover(albumId, file.parent, now)
        }

        val existing = entityRepo.findBySourcePath(relativePath)
        if (existing != null) {
            repairExistingTrackLinkage(existing, albumId, artistId)
            return
        }

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

        if (genre != null) {
            val candidateId = UUID.randomUUID()
            genreRepo.upsertByName(id = candidateId, name = genre)
            val genreId = genreRepo.findByName(genre)?.id ?: candidateId
            entityGenreRepo.save(EntityGenreJpa(entityId = entityId, genreId = genreId))
        }
    }

    private fun ensureArtist(
        artistName: String,
        now: OffsetDateTime,
    ): UUID {
        val artistSourceKey = "artist:${artistName.lowercase()}"
        val existing = entityRepo.findBySourcePath(artistSourceKey)
        if (existing != null) return existing.id
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
        return id
    }

    private fun ensureAlbum(
        albumName: String,
        artistName: String?,
        artistId: UUID?,
        now: OffsetDateTime,
    ): UUID {
        val albumSourceKey = "album:${artistName?.lowercase() ?: "unknown"}:${albumName.lowercase()}"
        val existing = entityRepo.findBySourcePath(albumSourceKey)
        if (existing != null) {
            // Repair album → artist link if it was created during the legacy "unknown" era
            if (artistId != null) {
                if (existing.parentId == null) {
                    existing.parentId = artistId
                    entityRepo.save(existing)
                }
                val albumRow = albumRepo.findById(existing.id).orElse(null)
                if (albumRow != null && albumRow.artistId == null) {
                    albumRow.artistId = artistId
                    albumRepo.save(albumRow)
                }
            }
            return existing.id
        }
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
        albumRepo.save(AlbumJpa(entityId = id, artistId = artistId))
        return id
    }

    private fun repairExistingTrackLinkage(
        existingTrack: LibraryEntityJpa,
        derivedAlbumId: UUID?,
        derivedArtistId: UUID?,
    ) {
        val trackRow = trackRepo.findById(existingTrack.id).orElse(null) ?: return
        var trackChanged = false
        if (trackRow.albumId == null && derivedAlbumId != null) {
            trackRow.albumId = derivedAlbumId
            trackChanged = true
        }
        if (trackRow.albumArtistId == null && derivedArtistId != null) {
            trackRow.albumArtistId = derivedArtistId
            trackChanged = true
        }
        if (trackChanged) trackRepo.save(trackRow)

        val effectiveAlbumId = trackRow.albumId
        if (existingTrack.parentId == null && effectiveAlbumId != null) {
            existingTrack.parentId = effectiveAlbumId
            entityRepo.save(existingTrack)
        }
        if (effectiveAlbumId != null && derivedArtistId != null) {
            val albumRow = albumRepo.findById(effectiveAlbumId).orElse(null)
            if (albumRow != null && albumRow.artistId == null) {
                albumRow.artistId = derivedArtistId
                albumRepo.save(albumRow)
                val albumEntity = entityRepo.findById(effectiveAlbumId).orElse(null)
                if (albumEntity != null && albumEntity.parentId == null) {
                    albumEntity.parentId = derivedArtistId
                    entityRepo.save(albumEntity)
                }
            }
        }
    }

    private fun ensureAlbumCover(
        albumId: UUID,
        albumDir: Path?,
        now: OffsetDateTime,
    ) {
        if (albumDir == null) return
        if (imageRepo.findByEntityIdAndIsPrimaryTrue(albumId) != null) return
        val coverPath = findCoverFile(albumDir) ?: return
        val sizeBytes = runCatching { Files.size(coverPath) }.getOrNull()
        imageRepo.save(
            ImageJpa(
                id = UUID.randomUUID(),
                entityId = albumId,
                imageType = "Primary",
                path = coverPath.toString(),
                sizeBytes = sizeBytes,
                isPrimary = true,
                createdAt = now,
            ),
        )
    }

    private fun findCoverFile(dir: Path): Path? {
        if (!Files.isDirectory(dir)) return null
        val coverNamesLower = coverFilenames.toSet()
        return runCatching {
            Files.newDirectoryStream(dir).use { stream ->
                stream.firstOrNull { p ->
                    Files.isRegularFile(p) && p.fileName.toString().lowercase() in coverNamesLower
                }
            }
        }.getOrNull()
    }

    private fun stripLeadingYear(folder: String): String = folder.replace(Regex("^\\d{4}\\s*-\\s*"), "").trim().ifBlank { folder }

    private fun Tag.safeGetFirst(field: FieldKey): String? =
        try {
            this.getFirst(field)
        } catch (_: UnsupportedOperationException) {
            null
        } catch (_: Exception) {
            null
        }
}
