package dev.yaytsa.worker.metadata

import dev.yaytsa.application.shared.port.Clock
import dev.yaytsa.persistence.library.entity.ImageJpa
import dev.yaytsa.persistence.library.repository.AlbumRepository
import dev.yaytsa.persistence.library.repository.ArtistRepository
import dev.yaytsa.persistence.library.repository.AudioTrackRepository
import dev.yaytsa.persistence.library.repository.EntityGenreRepository
import dev.yaytsa.persistence.library.repository.ImageRepository
import dev.yaytsa.persistence.library.repository.LibraryEntityRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

@Component
@ConditionalOnProperty(name = ["yaytsa.metadata.enabled"], havingValue = "true")
class MetadataEnricher(
    private val albumRepo: AlbumRepository,
    private val artistRepo: ArtistRepository,
    private val imageRepo: ImageRepository,
    private val trackRepo: AudioTrackRepository,
    private val entityRepo: LibraryEntityRepository,
    private val entityGenreRepo: EntityGenreRepository,
    private val clock: Clock,
    @Value("\${yaytsa.metadata.user-agent:Yaytsa/1.0 ( https://yay-tsa.com )}") private val userAgent: String,
    @Value("\${yaytsa.library.music-path:#{null}}") musicPath: String?,
    @Value("\${yaytsa.image.cover-cache-dir:#{null}}") coverCacheDir: String?,
    @Value("\${yaytsa.metadata.batch-size:50}") private val batchSize: Int,
    @Value("\${yaytsa.metadata.musicbrainz-base-url:https://musicbrainz.org/ws/2}") private val musicBrainzBaseUrl: String,
    @Value("\${yaytsa.metadata.coverart-base-url:https://coverartarchive.org}") private val coverArtBaseUrl: String,
    @Value("\${yaytsa.metadata.artist-image-dir:#{null}}") artistImageDir: String?,
    @Value("\${yaytsa.metadata.wikidata-base-url:https://www.wikidata.org/wiki}") wikidataBaseUrl: String,
    @Value("\${yaytsa.metadata.commons-api-url:https://commons.wikimedia.org/w/api.php}") commonsApiUrl: String,
    @Value("\${yaytsa.metadata.rate-limit-ms:1000}") rateLimitMs: Long,
    @Value("\${yaytsa.metadata.openlibrary-enabled:false}") private val openLibraryEnabled: Boolean,
    @Value("\${yaytsa.metadata.openlibrary-base-url:https://openlibrary.org}") openLibraryBaseUrl: String,
    @Value("\${yaytsa.metadata.openlibrary-covers-base-url:https://covers.openlibrary.org}") openLibraryCoversBaseUrl: String,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    private val audiobookGenres = listOf("audiobook", "audiobooks")

    private val safeRoot: Path? =
        musicPath
            ?.takeIf { it.isNotBlank() }
            ?.let { runCatching { Path.of(it).toRealPath() }.getOrNull() }

    private val artistImageRoot: Path? =
        artistImageDir
            ?.takeIf { it.isNotBlank() }
            ?.let { runCatching { Files.createDirectories(Path.of(it)).toRealPath() }.getOrNull() }

    private val coverCacheRoot: Path? =
        coverCacheDir
            ?.takeIf { it.isNotBlank() }
            ?.let { runCatching { Files.createDirectories(Path.of(it)).toRealPath() }.getOrNull() }

    private val rateLimiter = RateLimiter(clock, rateLimitMs)
    private val musicBrainz = MusicBrainzClient(musicBrainzBaseUrl, userAgent, rateLimiter)
    private val coverArt = CoverArtArchiveClient(coverArtBaseUrl, userAgent, rateLimiter)
    private val artistImageSource =
        ArtistImageSourceClient(userAgent, rateLimiter, musicBrainzBaseUrl, wikidataBaseUrl, commonsApiUrl)
    private val openLibrary =
        OpenLibraryCoverClient(openLibraryBaseUrl, openLibraryCoversBaseUrl, userAgent, rateLimiter)

    private fun now(): OffsetDateTime = OffsetDateTime.ofInstant(clock.now(), ZoneOffset.UTC)

    @Scheduled(fixedDelayString = "\${yaytsa.metadata.poll-interval-ms:300000}", initialDelay = 30_000)
    fun enrich() {
        log.info("Metadata enrichment cycle starting")
        val artists = enrichArtists()
        val albums = enrichAlbums()
        val audiobooks = enrichAudiobookTrackCovers()
        log.info(
            "Metadata enrichment cycle complete: {} artists, {} albums, {} audiobook tracks",
            artists,
            albums,
            audiobooks,
        )
    }

    private fun enrichArtists(): Int {
        // Re-attempt artwork for any artist still lacking a primary image (image-driven, self-healing),
        // unioned with the one-shot MusicBrainz-ID enrichment of never-checked artists.
        val pending =
            (artistRepo.findByMetadataCheckedAtIsNull(batchSize, 0) + artistRepo.findWithoutPrimaryImage(batchSize, 0))
                .distinctBy { it.entityId }
        var processed = 0
        for (artist in pending) {
            try {
                val name = entityRepo.findById(artist.entityId).orElse(null)?.name
                if (!name.isNullOrBlank()) {
                    val candidates = musicBrainz.searchArtists(name)
                    bestArtist(name, candidates)?.let { artist.musicbrainzId = it.mbid }
                }
                enrichArtistImageIfMissing(artist.entityId, artist.musicbrainzId)
                artist.metadataCheckedAt = now()
                artistRepo.save(artist)
                processed++
            } catch (e: MetadataProviderUnavailableException) {
                log.warn("Artist enrichment deferred (provider unavailable) for {}: {}", artist.entityId, e.message)
            } catch (e: Exception) {
                log.error("Artist enrichment permanently failed for {}; marking checked to unblock batch", artist.entityId, e)
                runCatching {
                    artist.metadataCheckedAt = now()
                    artistRepo.save(artist)
                }
            }
        }
        return processed
    }

    private fun enrichArtistImageIfMissing(
        artistId: UUID,
        musicbrainzId: String?,
    ) {
        if (artistImageRoot == null) return
        if (imageRepo.findByEntityIdAndIsPrimaryTrue(artistId) != null) return

        val external = musicbrainzId?.let { runCatching { artistImageSource.fetchArtistImage(it) }.getOrNull() }
        if (external != null && writeArtistImage(artistId, external.bytes, external.extension)) {
            log.info("Wrote external artist image for {}", artistId)
            return
        }

        val albumCover = findRepresentativeAlbumCover(artistId)
        if (albumCover != null) {
            val extension = albumCover.fileName.toString().substringAfterLast('.', "jpg")
            val bytes = runCatching { Files.readAllBytes(albumCover) }.getOrNull() ?: return
            if (writeArtistImage(artistId, bytes, extension)) {
                log.info("Wrote artist image for {} from album cover {}", artistId, albumCover)
            }
        }
    }

    private fun findRepresentativeAlbumCover(artistId: UUID): Path? =
        albumRepo
            .findByArtistId(artistId)
            .asSequence()
            .mapNotNull { imageRepo.findByEntityIdAndIsPrimaryTrue(it.entityId)?.path }
            .map { Path.of(it) }
            .firstOrNull { Files.isRegularFile(it) }

    private fun writeArtistImage(
        artistId: UUID,
        bytes: ByteArray,
        extension: String,
    ): Boolean {
        val root = artistImageRoot ?: return false
        if (bytes.isEmpty()) return false
        val safeExt = extension.takeIf { it.matches(Regex("^[a-z0-9]{1,5}$")) } ?: "jpg"
        val target = root.resolve("$artistId.$safeExt")
        val written =
            runCatching {
                Files.write(target, bytes)
            }.getOrElse {
                log.warn("Failed to write artist image to {}: {}", target, it.message)
                return false
            }
        imageRepo.save(
            ImageJpa(
                id = UUID.randomUUID(),
                entityId = artistId,
                imageType = "Primary",
                path = written.toAbsolutePath().toString(),
                sizeBytes = bytes.size.toLong(),
                isPrimary = true,
                createdAt = now(),
            ),
        )
        return true
    }

    private fun enrichAlbums(): Int {
        // Image-driven artwork backfill unioned with one-shot release-group MBID enrichment.
        val pending =
            (albumRepo.findByMetadataCheckedAtIsNull(batchSize, 0) + albumRepo.findWithoutPrimaryImage(batchSize, 0))
                .distinctBy { it.entityId }
        var processed = 0
        for (album in pending) {
            try {
                enrichAlbum(album.entityId)
                processed++
            } catch (e: MetadataProviderUnavailableException) {
                log.warn("Album enrichment deferred (provider unavailable) for {}: {}", album.entityId, e.message)
            } catch (e: Exception) {
                log.error("Album enrichment permanently failed for {}; marking checked to unblock batch", album.entityId, e)
                runCatching {
                    album.metadataCheckedAt = now()
                    albumRepo.save(album)
                }
            }
        }
        return processed
    }

    private fun enrichAlbum(albumId: UUID) {
        val album = albumRepo.findById(albumId).orElse(null) ?: return
        val albumName = entityRepo.findById(albumId).orElse(null)?.name
        val artistName = album.artistId?.let { entityRepo.findById(it).orElse(null)?.name }

        if (!albumName.isNullOrBlank()) {
            val local =
                LocalAlbum(
                    title = albumName,
                    artistName = artistName,
                    trackCount = album.totalTracks,
                    year = album.releaseDate?.year,
                )
            val candidates = musicBrainz.searchReleaseGroups(albumName, artistName)
            val match = ReleaseMatcher.match(local, candidates)
            if (match != null) {
                album.releaseGroupMbid = match.candidate.mbid
                downloadCoverIfMissing(albumId, match.candidate.mbid)
            }
        }

        album.metadataCheckedAt = now()
        albumRepo.save(album)
    }

    private fun downloadCoverIfMissing(
        albumId: UUID,
        releaseGroupMbid: String,
    ) {
        if (safeRoot == null) return
        if (imageRepo.findByEntityIdAndIsPrimaryTrue(albumId) != null) return

        val albumDir = resolveAlbumDirectory(albumId) ?: return
        if (!albumDir.startsWith(safeRoot)) {
            log.debug("Album directory {} outside music root, skipping cover write", albumDir)
            return
        }

        val cover = coverArt.fetchReleaseGroupFront(releaseGroupMbid) ?: return
        val coverFile = albumDir.resolve("cover.${cover.extension}")
        if (Files.exists(coverFile)) return

        val written =
            runCatching {
                Files.write(coverFile, cover.bytes, StandardOpenOption.CREATE_NEW)
            }.getOrElse {
                log.warn("Failed to write cover to {}: {}", coverFile, it.message)
                return
            }

        imageRepo.save(
            ImageJpa(
                id = UUID.randomUUID(),
                entityId = albumId,
                imageType = "Primary",
                path = written.toString(),
                sizeBytes = cover.bytes.size.toLong(),
                isPrimary = true,
                createdAt = now(),
            ),
        )
        log.info("Wrote cover art for album {} to {}", albumId, coverFile)
    }

    private fun resolveAlbumDirectory(albumId: UUID): Path? {
        val track = trackRepo.findByAlbumId(albumId).firstOrNull() ?: return null
        val entity = entityRepo.findById(track.entityId).orElse(null) ?: return null
        val rawPath = entity.sourcePath ?: return null
        val absolutePath =
            if (rawPath.startsWith("/")) {
                Path.of(rawPath)
            } else {
                val root = entity.libraryRoot ?: return null
                Path.of(root, rawPath)
            }
        val parent = absolutePath.parent ?: return null
        return runCatching { parent.toRealPath() }.getOrNull()
    }

    // Audiobook items are genre=Audiobook TRACKS, not albums, so the read path keys artwork on the
    // track's own entity id. For each such track still lacking a primary image, resolve a cover via a
    // universal-then-external chain and write a track-level Primary row pointing into the cover cache
    // (the media mount is typically read-only, so covers are cached in the writable cover dir).
    private fun enrichAudiobookTrackCovers(): Int {
        if (coverCacheRoot == null) return 0
        val pending = entityGenreRepo.findAudiobookTrackIdsWithoutPrimaryImage(audiobookGenres, batchSize, 0)
        var processed = 0
        for (trackId in pending) {
            try {
                if (enrichAudiobookTrackCover(trackId)) processed++
            } catch (e: MetadataProviderUnavailableException) {
                log.warn("Audiobook cover deferred (provider unavailable) for {}: {}", trackId, e.message)
            } catch (e: Exception) {
                log.warn("Audiobook cover enrichment failed for {}: {}", trackId, e.toString())
            }
        }
        return processed
    }

    private fun enrichAudiobookTrackCover(trackId: UUID): Boolean {
        if (imageRepo.findByEntityIdAndIsPrimaryTrue(trackId) != null) return false

        val track = trackRepo.findById(trackId).orElse(null)
        val borrowed = track?.albumId?.let { borrowParentCover(it) }
        if (borrowed != null && writeCachedCover(trackId, borrowed.first, borrowed.second)) {
            log.info("Borrowed parent album cover for audiobook track {}", trackId)
            return true
        }

        val title = entityRepo.findById(trackId).orElse(null)?.name ?: return false
        val artistName = track?.albumArtistId?.let { entityRepo.findById(it).orElse(null)?.name }

        val coverArtCover = fetchAudiobookCoverArt(track?.albumId)
        if (coverArtCover != null && writeCachedCover(trackId, coverArtCover.bytes, coverArtCover.extension)) {
            log.info("Wrote Cover Art Archive cover for audiobook track {}", trackId)
            return true
        }

        if (openLibraryEnabled) {
            val olCover = runCatching { openLibrary.fetchCover(title, artistName) }.getOrNull()
            if (olCover != null && writeCachedCover(trackId, olCover.bytes, olCover.extension)) {
                log.info("Wrote Open Library cover for audiobook track {}", trackId)
                return true
            }
        }
        return false
    }

    // Universal graph-walk fallback: borrow the parent album's already-resolved Primary cover bytes.
    private fun borrowParentCover(albumId: UUID): Pair<ByteArray, String>? {
        val image = imageRepo.findByEntityIdAndIsPrimaryTrue(albumId) ?: return null
        val path = Path.of(image.path)
        if (!Files.isRegularFile(path)) return null
        val bytes = runCatching { Files.readAllBytes(path) }.getOrNull() ?: return null
        val ext = path.fileName.toString().substringAfterLast('.', "jpg")
        return bytes to ext
    }

    private fun fetchAudiobookCoverArt(albumId: UUID?): CoverArt? {
        val album = albumId?.let { albumRepo.findById(it).orElse(null) } ?: return null
        val mbid = album.releaseGroupMbid
        if (mbid != null) return coverArt.fetchReleaseGroupFront(mbid)
        val albumName = entityRepo.findById(album.entityId).orElse(null)?.name ?: return null
        val artistName = album.artistId?.let { entityRepo.findById(it).orElse(null)?.name }
        val candidates = musicBrainz.searchReleaseGroups(albumName, artistName)
        val match =
            ReleaseMatcher.match(
                LocalAlbum(title = albumName, artistName = artistName, trackCount = album.totalTracks, year = album.releaseDate?.year),
                candidates,
            ) ?: return null
        album.releaseGroupMbid = match.candidate.mbid
        albumRepo.save(album)
        return coverArt.fetchReleaseGroupFront(match.candidate.mbid)
    }

    private fun writeCachedCover(
        entityId: UUID,
        bytes: ByteArray,
        extension: String,
    ): Boolean {
        val root = coverCacheRoot ?: return false
        if (bytes.isEmpty()) return false
        val safeExt = extension.takeIf { it.matches(Regex("^[a-z0-9]{1,5}$")) } ?: "jpg"
        val target = root.resolve("$entityId.$safeExt")
        val written =
            runCatching { Files.write(target, bytes) }
                .getOrElse {
                    log.warn("Failed to write cached cover to {}: {}", target, it.message)
                    return false
                }
        imageRepo.save(
            ImageJpa(
                id = UUID.randomUUID(),
                entityId = entityId,
                imageType = "Primary",
                path = written.toAbsolutePath().toString(),
                sizeBytes = bytes.size.toLong(),
                isPrimary = true,
                createdAt = now(),
            ),
        )
        return true
    }

    private fun bestArtist(
        name: String,
        candidates: List<MetadataCandidate>,
    ): MetadataCandidate? {
        if (candidates.isEmpty()) return null
        val ranked =
            candidates
                .map { it to ReleaseMatcher.normalizedEditDistance(name, it.title) }
                .sortedBy { it.second }
        val (best, distance) = ranked.first()
        return if (distance <= 0.15 && best.score >= 80) best else null
    }
}
