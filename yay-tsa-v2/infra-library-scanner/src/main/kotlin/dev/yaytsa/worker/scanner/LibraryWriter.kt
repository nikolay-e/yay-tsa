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

        // Issue #213: ~7000 FLAC files have ID3 titles that don't match their filenames
        // (the tag for "01 - In The Heart Of Stone.flac" says "November 3023"). When the
        // filename has a canonical track-number prefix AND the ID3 title disagrees with
        // the filename body, treat the filename as authoritative — Yaytsa has no UI to
        // edit track titles, so a tag/filename divergence is always corruption from an
        // upstream tagging tool, never user intent.
        val filenameTitle = stripTrackNumberPrefix(file.nameWithoutExtension)
        val id3Title = audioTag?.safeGetFirst(FieldKey.TITLE)?.usableTag()
        val filenameHasTrackPrefix = Regex("^\\d{1,3}\\s*[-._]\\s*").containsMatchIn(file.nameWithoutExtension)
        val trackName =
            when {
                id3Title == null -> filenameTitle
                filenameHasTrackPrefix && !filenameTitle.equals(id3Title, ignoreCase = true) -> filenameTitle
                else -> id3Title
            }
        val tagAlbumArtist = audioTag?.safeGetFirst(FieldKey.ALBUM_ARTIST)?.usableTag()
        val tagArtist = audioTag?.safeGetFirst(FieldKey.ARTIST)?.usableTag()
        val tagAlbum = audioTag?.safeGetFirst(FieldKey.ALBUM)?.usableTag()

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

        val rawArtist = tagAlbumArtist ?: tagArtist ?: folderArtist
        val artistName = primaryArtist(rawArtist)
        val albumName = tagAlbum ?: folderAlbum

        val trackNumber = audioTag?.safeGetFirst(FieldKey.TRACK)?.toIntOrNull()
        val discNumber = audioTag?.safeGetFirst(FieldKey.DISC_NO)?.toIntOrNull() ?: 1
        val year = audioTag?.safeGetFirst(FieldKey.YEAR)?.toIntOrNull()
        val genre = audioTag?.safeGetFirst(FieldKey.GENRE)?.takeIf { it.isNotBlank() }
        val durationMs = audioHeader?.trackLength?.let { it * 1000L }
        val bitrate = audioHeader?.bitRateAsNumber?.toInt()
        val sampleRate = audioHeader?.sampleRateAsNumber?.toInt()
        val channels = audioHeader?.channels?.toIntOrNull()

        // Skip unplayable files: zero/missing duration usually means CD pre-gap files
        // (e.g. "00 - pregap.flac") or audio the scanner couldn't decode. Clients can't
        // play them and they pollute album track lists with phantom entries.
        if (durationMs == null || durationMs <= 0L) {
            return
        }

        val now = OffsetDateTime.ofInstant(clock.now(), ZoneOffset.UTC)

        val artistId = artistName?.let { ensureArtist(it, now) }
        val albumId = albumName?.let { ensureAlbum(it, artistName, artistId, now) }

        if (albumId != null) {
            ensureAlbumCover(albumId, file.parent, now)
        }

        val existing =
            entityRepo.findBySourcePath(relativePath)
                ?: entityRepo.findTrackBySourcePathSuffix("%/$relativePath")?.also { stale ->
                    // v1 ETL row with absolute path like "/media/.../track.flac";
                    // rewrite to the canonical relative path so this stays idempotent.
                    stale.sourcePath = relativePath
                    if (stale.libraryRoot == null) stale.libraryRoot = root.toString()
                    entityRepo.save(stale)
                }
        if (existing != null) {
            repairExistingTrackLinkage(existing, albumId, artistId, trackName)
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
        // Case-insensitive lookup heals legacy ETL rows whose source_path
        // capitalization differs from the current scanner's `.lowercase()` key.
        // Postgres `lower()` in default C locale does not case-fold Cyrillic;
        // the repo method uses ICU collation. Without this, every scan of
        // tracks like Кино/Король и Шут re-creates a sibling artist entity.
        val existing =
            entityRepo.findBySourcePath(artistSourceKey)
                ?: entityRepo.findBySourcePathCaseInsensitive(artistSourceKey, "ARTIST")?.also { stale ->
                    if (stale.sourcePath != artistSourceKey) {
                        stale.sourcePath = artistSourceKey
                        entityRepo.save(stale)
                    }
                }
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
        // See ensureArtist comment — same ICU-aware healing for Cyrillic/casing.
        val existing =
            entityRepo.findBySourcePath(albumSourceKey)
                ?: entityRepo.findBySourcePathCaseInsensitive(albumSourceKey, "ALBUM")?.also { stale ->
                    if (stale.sourcePath != albumSourceKey) {
                        stale.sourcePath = albumSourceKey
                        entityRepo.save(stale)
                    }
                }
        if (existing != null) {
            // Always realign album → primary artist (overwrite legacy compound or "unknown" links)
            if (artistId != null) {
                if (existing.parentId != artistId) {
                    existing.parentId = artistId
                    entityRepo.save(existing)
                }
                val albumRow = albumRepo.findById(existing.id).orElse(null)
                if (albumRow != null && albumRow.artistId != artistId) {
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
        derivedName: String,
    ) {
        val trackRow = trackRepo.findById(existingTrack.id).orElse(null) ?: return
        var trackChanged = false
        if (trackRow.albumId == null && derivedAlbumId != null) {
            trackRow.albumId = derivedAlbumId
            trackChanged = true
        }
        if (derivedArtistId != null && trackRow.albumArtistId != derivedArtistId) {
            trackRow.albumArtistId = derivedArtistId
            trackChanged = true
        }
        if (trackChanged) trackRepo.save(trackRow)

        // Repair stale titles. Two cases overwrite the DB row from the freshly-read tag:
        //   1) existing name is a filename-fallback ("01 - Eyeless")
        //   2) existing name diverges from the file's actual ID3 title — v1 ETL rows
        //      from earlier scans had titles that didn't match the filename (e.g. row
        //      for "01 - In The Heart Of Stone.flac" had name="November 3023"), see
        //      issue #213. The tag we just read is authoritative for the same file.
        if (derivedName.isNotBlank() && derivedName != existingTrack.name) {
            existingTrack.name = derivedName
            existingTrack.sortName = derivedName.lowercase()
            entityRepo.save(existingTrack)
        }

        val effectiveAlbumId = trackRow.albumId
        if (effectiveAlbumId != null && existingTrack.parentId != effectiveAlbumId) {
            existingTrack.parentId = effectiveAlbumId
            entityRepo.save(existingTrack)
        }
        if (effectiveAlbumId != null && derivedArtistId != null) {
            val albumRow = albumRepo.findById(effectiveAlbumId).orElse(null)
            if (albumRow != null && albumRow.artistId != derivedArtistId) {
                albumRow.artistId = derivedArtistId
                albumRepo.save(albumRow)
                val albumEntity = entityRepo.findById(effectiveAlbumId).orElse(null)
                if (albumEntity != null && albumEntity.parentId != derivedArtistId) {
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
        val existing = imageRepo.findByEntityIdAndIsPrimaryTrue(albumId)
        if (existing != null && Files.isRegularFile(Path.of(existing.path))) return
        val coverPath = findCoverFile(albumDir) ?: return
        if (existing != null) imageRepo.delete(existing)
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

    // Filename-derived titles often start with a track-number prefix ("01 - Eyeless").
    // Strip it so the player shows "Eyeless" instead of leaking the filename pattern.
    private fun stripTrackNumberPrefix(filename: String): String = filename.replace(Regex("^\\d{1,3}\\s*[-._]\\s*"), "").trim().ifBlank { filename }

    // Some FLAC rips carry placeholder tags like "##### ######" or "????" where the
    // ripper failed to encode Cyrillic. Treat them as missing so folder/filename
    // fallback kicks in.
    private fun String.usableTag(): String? = takeIf { it.isNotBlank() && !it.matches(Regex("^[#?\\s]+$")) }

    private val artistDelimiters =
        Regex(
            "\\s*[,;/&]\\s+|\\s+(?:feat\\.|ft\\.|featuring|vs\\.|vs|with|x)\\s+",
            RegexOption.IGNORE_CASE,
        )

    private fun primaryArtist(name: String?): String? =
        name
            ?.split(artistDelimiters)
            ?.firstOrNull()
            ?.trim()
            ?.takeIf { it.isNotBlank() }

    fun deleteOrphanArtists(): Int = entityRepo.deleteOrphanArtists()

    private fun Tag.safeGetFirst(field: FieldKey): String? =
        try {
            this.getFirst(field)
        } catch (_: UnsupportedOperationException) {
            null
        } catch (_: Exception) {
            null
        }
}
