package dev.yaytsa.worker.scanner

import dev.yaytsa.application.shared.port.Clock
import dev.yaytsa.persistence.library.AlbumCoverFilenames
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
import dev.yaytsa.shared.AudiobookGenres
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import org.jaudiotagger.tag.Tag
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.nio.file.Files
import java.nio.file.Path
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
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
    private val embeddedCovers: EmbeddedCoverExtractor,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    class ScanSession internal constructor() {
        internal val artistIdsByKey = HashMap<String, UUID>()
        internal val albumIdsByKey = HashMap<String, UUID>()
        internal val albumsWithVerifiedCover = HashSet<UUID>()
        internal var legacyNullRootRowsExist: Boolean? = null
    }

    fun newScanSession(): ScanSession = ScanSession()

    private data class ProbedAudioFile(
        val trackName: String,
        val artistName: String?,
        val albumName: String?,
        val trackNumber: Int?,
        val discNumber: Int,
        val year: Int?,
        val genre: String?,
        val durationMs: Long,
        val bitrate: Int?,
        val sampleRate: Int?,
        val channels: Int?,
        val albumRootDir: Path?,
        val replayGain: ReplayGainInfo,
    )

    @Transactional
    fun upsertTrack(
        root: Path,
        file: Path,
        session: ScanSession = ScanSession(),
    ): UUID? {
        val relativePath = root.relativize(file).toString()
        val codec = file.extension.lowercase()
        val sizeBytes = Files.size(file)
        val mtime = OffsetDateTime.ofInstant(Files.getLastModifiedTime(file).toInstant(), ZoneOffset.UTC)

        var claimedRename = false
        val existing =
            entityRepo.findBySourcePath(relativePath)
                ?: healLegacyAbsolutePathRow(root, relativePath, session)
                ?: claimRenamedTrack(root, relativePath, sizeBytes, mtime)?.also { claimedRename = true }

        if (existing != null && !claimedRename && fileUnchanged(existing, sizeBytes, mtime)) {
            if (existing.libraryRoot != root.toString()) {
                existing.libraryRoot = root.toString()
                entityRepo.save(existing)
            }
            return existing.id
        }

        val probed = probeAudioFile(root, file) ?: return existing?.id
        val now = OffsetDateTime.ofInstant(clock.now(), ZoneOffset.UTC)

        val artistId = probed.artistName?.let { ensureArtist(it, now, session) }
        val albumId = probed.albumName?.let { ensureAlbum(it, probed.artistName, artistId, now, session) }

        if (albumId != null) {
            ensureAlbumCover(albumId, file.parent, probed.albumRootDir, file, now, session)
        }

        val searchText =
            listOfNotNull(probed.trackName, probed.artistName, probed.albumName)
                .joinToString(" ") { it.lowercase() }

        if (existing != null) {
            refreshTrackMetadata(existing, root, codec, sizeBytes, mtime, searchText, probed, albumId, artistId, now)
            ensureAudiobookTrackCover(existing.id, probed.genre, file, now)
            return existing.id
        }

        val entityId = UUID.randomUUID()
        entityRepo.save(
            LibraryEntityJpa(
                id = entityId,
                entityType = "TRACK",
                name = probed.trackName,
                sortName = probed.trackName.lowercase(),
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
                trackNumber = probed.trackNumber,
                discNumber = probed.discNumber,
                durationMs = probed.durationMs,
                bitrate = probed.bitrate,
                sampleRate = probed.sampleRate,
                channels = probed.channels,
                year = probed.year,
                codec = codec,
                replaygainTrackGain = probed.replayGain.trackGain,
                replaygainAlbumGain = probed.replayGain.albumGain,
                replaygainTrackPeak = probed.replayGain.trackPeak,
                replaygainCheckedAt = now,
            ),
        )

        linkGenre(entityId, probed.genre)
        ensureAudiobookTrackCover(entityId, probed.genre, file, now)

        return entityId
    }

    private fun isAudiobook(genre: String?): Boolean = normalizeGenres(genre).any { it.lowercase() in AudiobookGenres.names }

    // Each /audiobooks item is a Track (genre=Audiobook), not an album, so the read path
    // (TrackProjection imageTags / getPrimaryImage) keys on the track's OWN entity id. Materialize
    // embedded art into a track-level Primary image row so the cover is both advertised and servable
    // by construction — no external lookup, no advertise-but-can't-serve asymmetry. Idempotent
    // (skip when a row already exists), bounded (only audiobook tracks with embedded art).
    private fun ensureAudiobookTrackCover(
        trackId: UUID,
        genre: String?,
        file: Path,
        now: OffsetDateTime,
    ) {
        if (!isAudiobook(genre)) return
        val existing = imageRepo.findByEntityIdAndIsPrimaryTrue(trackId)
        if (existing != null && Files.isRegularFile(Path.of(existing.path))) return
        val extracted = embeddedCovers.extractToCache(file) ?: return
        // Flush the delete before the insert: Hibernate orders inserts before deletes within a
        // batch, so a bare delete+save of the same entity's Primary row inserts the new one first
        // and trips the idx_images_one_primary unique index, aborting the whole upsertTrack tx.
        if (existing != null) {
            imageRepo.delete(existing)
            imageRepo.flush()
        }
        imageRepo.save(
            ImageJpa(
                id = UUID.randomUUID(),
                entityId = trackId,
                imageType = "Primary",
                path = extracted.path.toAbsolutePath().toString(),
                sizeBytes = extracted.sizeBytes,
                isPrimary = true,
                createdAt = now,
            ),
        )
    }

    private fun fileUnchanged(
        existing: LibraryEntityJpa,
        sizeBytes: Long,
        mtime: OffsetDateTime,
    ): Boolean =
        existing.sizeBytes == sizeBytes &&
            existing.mtime?.toInstant()?.toEpochMilli() == mtime.toInstant().toEpochMilli()

    private fun healLegacyAbsolutePathRow(
        root: Path,
        relativePath: String,
        session: ScanSession,
    ): LibraryEntityJpa? {
        // v1 ETL rows carry an absolute source_path and a NULL library_root. The
        // leading-wildcard LIKE is a seq scan, so only attempt it while such rows exist.
        val legacyRowsExist =
            session.legacyNullRootRowsExist
                ?: entityRepo.existsTrackWithNullLibraryRoot().also { session.legacyNullRootRowsExist = it }
        if (!legacyRowsExist) return null
        return entityRepo.findTrackBySourcePathSuffix("%/$relativePath")?.also { stale ->
            stale.sourcePath = relativePath
            if (stale.libraryRoot == null) stale.libraryRoot = root.toString()
            entityRepo.save(stale)
        }
    }

    private fun claimRenamedTrack(
        root: Path,
        relativePath: String,
        sizeBytes: Long,
        mtime: OffsetDateTime,
    ): LibraryEntityJpa? {
        val mtimeLow = OffsetDateTime.ofInstant(mtime.toInstant().truncatedTo(ChronoUnit.MILLIS), ZoneOffset.UTC)
        val match =
            entityRepo
                .findTracksBySizeAndMtimeWithin(sizeBytes, mtimeLow, mtimeLow.plus(1, ChronoUnit.MILLIS))
                .firstOrNull { candidate ->
                    val candidatePath = candidate.sourcePath
                    candidatePath != null &&
                        candidatePath != relativePath &&
                        !Files.exists(Path.of(candidate.libraryRoot ?: root.toString()).resolve(candidatePath))
                } ?: return null
        log.info("Detected renamed track {} -> {}, preserving entity {}", match.sourcePath, relativePath, match.id)
        match.sourcePath = relativePath
        match.libraryRoot = root.toString()
        entityRepo.save(match)
        return match
    }

    private fun probeAudioFile(
        root: Path,
        file: Path,
    ): ProbedAudioFile? {
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
        val rawTrackName =
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

        // Album root is <Artist>/<Album>; for multi-disc layouts the track lives a level
        // deeper (…/<Album>/CD1/track.flac), so cover art at the album root is found by
        // falling back from the track's own directory to this path.
        val albumRootDir = if (pathSegments.size >= 3) root.resolve(pathSegments[0]).resolve(pathSegments[1]) else null

        val effectiveArtist = primaryArtist(tagAlbumArtist ?: tagArtist ?: folderArtist)
        val trackName = stripTrailingAudioExtension(stripArtistPrefix(rawTrackName, effectiveArtist))

        val durationMs = audioHeader?.trackLength?.let { it * 1000L }

        // Skip unplayable files: missing duration, zero, or <2s (CD pre-gap files like
        // "00 - pregap.flac", corrupt FLACs with bogus 1.0s LENGTH headers, or audio the
        // scanner couldn't decode). Clients can't usefully play them and they pollute
        // album track lists with phantom entries — Fixes #217.
        if (durationMs == null || durationMs < MIN_PLAYABLE_DURATION_MS) {
            return null
        }

        return ProbedAudioFile(
            trackName = trackName,
            artistName = effectiveArtist,
            albumName = tagAlbum ?: folderAlbum,
            trackNumber = audioTag?.safeGetFirst(FieldKey.TRACK)?.leadingInt(),
            discNumber =
                audioTag?.safeGetFirst(FieldKey.DISC_NO)?.leadingInt()
                    ?: discNumberFromPath(pathSegments.dropLast(1))
                    ?: 1,
            year = audioTag?.safeGetFirst(FieldKey.YEAR)?.leadingInt(),
            genre = audioTag?.safeGetFirst(FieldKey.GENRE)?.takeIf { it.isNotBlank() },
            durationMs = durationMs,
            bitrate = audioHeader?.bitRateAsNumber?.toInt(),
            sampleRate = audioHeader?.sampleRateAsNumber?.toInt(),
            channels = audioHeader?.channels?.toIntOrNull(),
            albumRootDir = albumRootDir,
            replayGain = ReplayGainTags.read(audioTag),
        )
    }

    private fun refreshTrackMetadata(
        existing: LibraryEntityJpa,
        root: Path,
        codec: String,
        sizeBytes: Long,
        mtime: OffsetDateTime,
        searchText: String,
        probed: ProbedAudioFile,
        albumId: UUID?,
        artistId: UUID?,
        now: OffsetDateTime,
    ) {
        existing.name = probed.trackName
        existing.sortName = probed.trackName.lowercase()
        existing.searchText = searchText
        existing.container = codec
        existing.sizeBytes = sizeBytes
        existing.mtime = mtime
        existing.libraryRoot = root.toString()
        existing.updatedAt = now
        entityRepo.save(existing)

        val trackRow = trackRepo.findById(existing.id).orElse(null)
        if (trackRow != null) {
            trackRow.trackNumber = probed.trackNumber
            trackRow.discNumber = probed.discNumber
            trackRow.durationMs = probed.durationMs
            trackRow.bitrate = probed.bitrate
            trackRow.sampleRate = probed.sampleRate
            trackRow.channels = probed.channels
            trackRow.year = probed.year
            trackRow.codec = codec
            trackRow.replaygainTrackGain = probed.replayGain.trackGain
            trackRow.replaygainAlbumGain = probed.replayGain.albumGain
            trackRow.replaygainTrackPeak = probed.replayGain.trackPeak
            trackRow.replaygainCheckedAt = now
            trackRepo.save(trackRow)
        }

        repairExistingTrackLinkage(existing, albumId, artistId)

        entityRepo.deleteEntityGenresByEntityIds(listOf(existing.id))
        linkGenre(existing.id, probed.genre)
    }

    // Genres are overlapping/nested sets, not one-per-track buckets: a "symphonic metal, heavy metal,
    // power metal" tag is three memberships. Split the raw multi-value tag into atomic genres and
    // case-normalize (initcap) each so a track tagged "power metal" and one tagged inside a combo share
    // the same "Power Metal". Subset relationships between the atomic genres are derived separately in
    // core_v2_library.genre_relations (see migration).
    private fun linkGenre(
        entityId: UUID,
        genre: String?,
    ) {
        for (atomic in normalizeGenres(genre)) {
            val candidateId = UUID.randomUUID()
            genreRepo.upsertByName(id = candidateId, name = atomic)
            val genreId = genreRepo.findByName(atomic)?.id ?: candidateId
            entityGenreRepo.save(EntityGenreJpa(entityId = entityId, genreId = genreId))
        }
    }

    private fun normalizeGenres(raw: String?): List<String> =
        raw
            ?.split(GENRE_DELIMITERS)
            ?.map { titleCaseGenre(it.trim().replace(WHITESPACE, " ")) }
            ?.filter { it.isNotEmpty() }
            ?.distinct()
            .orEmpty()

    // Mirror Postgres initcap so scanner-written names match the migration's normalization exactly:
    // the first letter of each alphanumeric run is uppercased, the rest lowercased.
    private fun titleCaseGenre(s: String): String = GENRE_WORD.replace(s) { m -> m.value.lowercase().replaceFirstChar { it.uppercaseChar() } }

    private fun ensureArtist(
        artistName: String,
        now: OffsetDateTime,
        session: ScanSession,
    ): UUID {
        val artistSourceKey = "artist:${artistName.lowercase()}"
        session.artistIdsByKey[artistSourceKey]?.let { return it }
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
        if (existing != null) {
            session.artistIdsByKey[artistSourceKey] = existing.id
            return existing.id
        }
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
        session.artistIdsByKey[artistSourceKey] = id
        return id
    }

    private fun ensureAlbum(
        albumName: String,
        artistName: String?,
        artistId: UUID?,
        now: OffsetDateTime,
        session: ScanSession,
    ): UUID {
        val albumSourceKey = "album:${artistName?.lowercase() ?: "unknown"}:${albumName.lowercase()}"
        val memoKey = "$albumSourceKey|$artistId"
        session.albumIdsByKey[memoKey]?.let { return it }
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
            session.albumIdsByKey[memoKey] = existing.id
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
        session.albumIdsByKey[memoKey] = id
        return id
    }

    private fun repairExistingTrackLinkage(
        existingTrack: LibraryEntityJpa,
        derivedAlbumId: UUID?,
        derivedArtistId: UUID?,
    ) {
        val trackRow = trackRepo.findById(existingTrack.id).orElse(null) ?: return
        var trackChanged = false
        if (derivedAlbumId != null && trackRow.albumId != derivedAlbumId) {
            trackRow.albumId = derivedAlbumId
            trackChanged = true
        }
        if (derivedArtistId != null && trackRow.albumArtistId != derivedArtistId) {
            trackRow.albumArtistId = derivedArtistId
            trackChanged = true
        }
        if (trackChanged) trackRepo.save(trackRow)

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
        trackDir: Path?,
        albumRootDir: Path?,
        trackFile: Path,
        now: OffsetDateTime,
        session: ScanSession,
    ) {
        if (albumId in session.albumsWithVerifiedCover) return
        val existing = imageRepo.findByEntityIdAndIsPrimaryTrue(albumId)
        if (existing != null && Files.isRegularFile(Path.of(existing.path))) {
            session.albumsWithVerifiedCover.add(albumId)
            return
        }
        // Prefer a cover next to the track (single-disc albums, or per-disc art); fall
        // back to the album root so multi-disc albums whose cover sits at <Album>/ resolve;
        // last, materialize the track's embedded artwork into the cover cache so albums whose
        // files carry only tag art still get an indexed Primary row (advertised == servable).
        val (coverPath, sizeBytes) =
            (trackDir?.let(::findCoverFile) ?: albumRootDir?.let(::findCoverFile))
                ?.let { it to runCatching { Files.size(it) }.getOrNull() }
                ?: embeddedCovers.extractToCache(trackFile)?.let { it.path.toAbsolutePath() to it.sizeBytes }
                ?: return
        // Flush the delete before the insert (see ensureAudiobookTrackCover): Hibernate's
        // insert-before-delete batch ordering otherwise trips idx_images_one_primary and aborts
        // the whole upsertTrack tx, so the track's row is never persisted and its stale path 404s.
        if (existing != null) {
            imageRepo.delete(existing)
            imageRepo.flush()
        }
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
        session.albumsWithVerifiedCover.add(albumId)
    }

    private fun findCoverFile(dir: Path): Path? {
        if (!Files.isDirectory(dir)) return null
        val coverNamesLower = AlbumCoverFilenames.all.toSet()
        return runCatching {
            Files.newDirectoryStream(dir).use { stream ->
                stream.firstOrNull { p ->
                    Files.isRegularFile(p) && p.fileName.toString().lowercase() in coverNamesLower
                }
            }
        }.getOrNull()
    }

    private fun stripLeadingYear(folder: String): String = folder.replace(Regex("^\\d{4}\\s*-\\s*"), "").trim().ifBlank { folder }

    // Many multi-disc rips carry the disc number only in the "CD2"/"Disc 2"/"Disk 2" folder name,
    // not in a DISC_NO/TPOS tag. Without this, every disc defaults to 1, so CD1-track-1 and
    // CD2-track-1 collide and the album's track order becomes nondeterministic. Directory segments
    // only (never the filename); the highest match wins so a stray "CD" in an artist/album name
    // higher up the path can't override the real disc folder nearer the track.
    private fun discNumberFromPath(directorySegments: List<String>): Int? =
        directorySegments
            .mapNotNull { seg ->
                DISC_FOLDER_PATTERN
                    .find(seg.trim())
                    ?.groupValues
                    ?.get(1)
                    ?.toIntOrNull()
            }.lastOrNull()

    // Filename-derived titles often start with a track-number prefix ("01 - Eyeless").
    // Strip it so the player shows "Eyeless" instead of leaking the filename pattern.
    private fun stripTrackNumberPrefix(filename: String): String = filename.replace(Regex("^\\d{1,3}\\s*[-._]\\s*"), "").trim().ifBlank { filename }

    // A real title never ends in an audio-file extension; when it does, the source's TITLE tag was set
    // to the filename (e.g. "Lament.flac") and the leak surfaces verbatim in the library UI. Strip it.
    private fun stripTrailingAudioExtension(title: String): String =
        title.replace(Regex("\\.(flac|mp3|wav|ogg|m4a|aac|wma|opus)$", RegexOption.IGNORE_CASE), "").trim().ifBlank { title }

    // Some upstream taggers prefix the TITLE with the performer ("The Hatters - Свадьба"). When the
    // segment before " - " equals the resolved artist (case-insensitive), it is redundant duplication of
    // the artist, never the real title, so strip it. Conservative by construction: only an exact artist
    // match is removed (collaborations like "Рудбой, The Hatters - …" don't match the album artist and
    // are left intact), and never to an empty remainder.
    private fun stripArtistPrefix(
        title: String,
        artist: String?,
    ): String {
        if (artist.isNullOrBlank()) return title
        val prefix = "$artist - "
        if (title.length > prefix.length && title.startsWith(prefix, ignoreCase = true)) {
            val rest = title.substring(prefix.length).trim()
            if (rest.isNotBlank()) return rest
        }
        return title
    }

    // Some FLAC rips carry placeholder tags like "##### ######" or "????" where the
    // ripper failed to encode Cyrillic. Treat them as missing so folder/filename
    // fallback kicks in.
    private fun String.usableTag(): String? =
        takeIf {
            it.isNotBlank() &&
                !it.matches(Regex("^[-#?\\s]+$")) &&
                !it.trim().equals("n/a", ignoreCase = true)
        }

    // Tags like "1/12" (track-of-total) and "2008-05-12" (full release date) must
    // still yield the leading number instead of failing toIntOrNull entirely.
    private fun String.leadingInt(): Int? = Regex("^\\d+").find(trim())?.value?.toIntOrNull()

    // ";" is the only unambiguous multi-artist separator: "&", "," and "/" appear in
    // legitimate band names (Simon & Garfunkel, Crosby, Stills & Nash, AC/DC).
    private val artistDelimiters =
        Regex(
            "\\s*;\\s*|\\s+(?:feat\\.|ft\\.|featuring|vs\\.|vs|with|x)\\s+",
            RegexOption.IGNORE_CASE,
        )

    private fun primaryArtist(name: String?): String? =
        name
            ?.split(artistDelimiters)
            ?.firstOrNull()
            ?.trim()
            ?.takeIf { it.isNotBlank() }

    @Transactional
    fun deleteVanishedTracks(
        root: Path,
        presentSourcePaths: Set<String>,
    ): Int {
        if (presentSourcePaths.isEmpty()) {
            log.warn("Walk of {} yielded no audio files; refusing vanished-track reconcile (unmounted volume?)", root)
            return 0
        }
        val rootKey = root.toString()
        // Include legacy NULL-library_root ghost rows in the candidate set. source_path is stored
        // RELATIVE, so a path prefix can't attribute a NULL row to a root — instead we let the
        // present-paths filter below delete any NULL-root row whose path isn't on disk (the v1
        // ghost case). A NULL-root row that IS present is kept (a valid track that lost its root).
        val candidates = entityRepo.findTrackIdSourcePathsByLibraryRootOrNull(rootKey)
        val vanished =
            candidates
                .filterNot { (it.getOrNull(1) as? String) in presentSourcePaths }
                .mapNotNull { it.getOrNull(0) as? UUID }
        if (vanished.isEmpty()) return 0
        if (vanished.size * 2 > candidates.size) {
            log.warn(
                "Refusing to delete {} of {} tracks for root {} (>50% vanished); verify the volume is fully mounted",
                vanished.size,
                candidates.size,
                root,
            )
            return 0
        }
        vanished.chunked(DELETE_BATCH_SIZE).forEach { batch ->
            entityRepo.deleteEntityGenresByEntityIds(batch)
            entityRepo.deleteImagesByEntityIds(batch)
            entityRepo.deleteAudioTracksByEntityIds(batch)
            entityRepo.deleteEntitiesByIds(batch)
        }
        return vanished.size
    }

    @Transactional
    fun deleteOrphanAlbums(): Int {
        entityRepo.deleteAlbumRowsWithoutTracks()
        return entityRepo.deleteOrphanAlbums()
    }

    fun deleteOrphanArtists(): Int = entityRepo.deleteOrphanArtists()

    // Refresh the derived genre subset/nesting edges after a scan may have introduced new genres.
    @Transactional
    fun rebuildGenreRelations() = genreRepo.rebuildGenreRelations()

    private fun Tag.safeGetFirst(field: FieldKey): String? =
        try {
            this.getFirst(field)
        } catch (_: UnsupportedOperationException) {
            null
        } catch (_: Exception) {
            null
        }

    companion object {
        // Tracks shorter than this are usually CD pre-gap files, corrupt FLACs with
        // placeholder 1s LENGTH headers, or junk the scanner couldn't decode properly.
        private const val MIN_PLAYABLE_DURATION_MS = 2000L
        private const val DELETE_BATCH_SIZE = 500

        // Multi-value genre-tag separators. Hyphen is intentionally excluded (it lives inside genres
        // like "Nu-Metal" / "Trip-Hop"); " - " combos are rare enough to leave alone.
        private val GENRE_DELIMITERS = Regex("[,;|/]")
        private val WHITESPACE = Regex("\\s+")
        private val GENRE_WORD = Regex("[\\p{L}\\p{N}]+")

        // Matches disc-folder names: "CD2", "CD 2", "Disc 2", "Disk_2" (case-insensitive), whole segment.
        private val DISC_FOLDER_PATTERN = Regex("(?i)^(?:cd|disc|disk)\\s*[-_]?\\s*(\\d{1,2})$")
    }
}
