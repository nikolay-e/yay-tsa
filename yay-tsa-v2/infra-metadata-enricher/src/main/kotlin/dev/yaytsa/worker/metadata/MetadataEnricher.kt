package dev.yaytsa.worker.metadata

import dev.yaytsa.application.shared.port.Clock
import dev.yaytsa.persistence.library.entity.ImageJpa
import dev.yaytsa.persistence.library.repository.AlbumRepository
import dev.yaytsa.persistence.library.repository.ArtistRepository
import dev.yaytsa.persistence.library.repository.AudioTrackRepository
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
    private val clock: Clock,
    @Value("\${yaytsa.metadata.user-agent:Yaytsa/1.0 ( https://yay-tsa.com )}") private val userAgent: String,
    @Value("\${yaytsa.library.music-path:#{null}}") musicPath: String?,
    @Value("\${yaytsa.metadata.batch-size:50}") private val batchSize: Int,
    @Value("\${yaytsa.metadata.musicbrainz-base-url:https://musicbrainz.org/ws/2}") private val musicBrainzBaseUrl: String,
    @Value("\${yaytsa.metadata.coverart-base-url:https://coverartarchive.org}") private val coverArtBaseUrl: String,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    private val safeRoot: Path? =
        musicPath
            ?.takeIf { it.isNotBlank() }
            ?.let { runCatching { Path.of(it).toRealPath() }.getOrNull() }

    private val rateLimiter = RateLimiter(clock)
    private val musicBrainz = MusicBrainzClient(musicBrainzBaseUrl, userAgent, rateLimiter)
    private val coverArt = CoverArtArchiveClient(coverArtBaseUrl, userAgent, rateLimiter)

    private fun now(): OffsetDateTime = OffsetDateTime.ofInstant(clock.now(), ZoneOffset.UTC)

    @Scheduled(fixedDelayString = "\${yaytsa.metadata.poll-interval-ms:300000}", initialDelay = 30_000)
    fun enrich() {
        log.info("Metadata enrichment cycle starting")
        val artists = enrichArtists()
        val albums = enrichAlbums()
        log.info("Metadata enrichment cycle complete: {} artists, {} albums", artists, albums)
    }

    private fun enrichArtists(): Int {
        val pending = artistRepo.findByMetadataCheckedAtIsNull(batchSize, 0)
        var processed = 0
        for (artist in pending) {
            try {
                val name = entityRepo.findById(artist.entityId).orElse(null)?.name
                if (!name.isNullOrBlank()) {
                    val candidates = musicBrainz.searchArtists(name)
                    bestArtist(name, candidates)?.let { artist.musicbrainzId = it.mbid }
                }
                artist.metadataCheckedAt = now()
                artistRepo.save(artist)
                processed++
            } catch (e: Exception) {
                log.warn("Artist metadata enrichment failed for {}", artist.entityId, e)
            }
        }
        return processed
    }

    private fun enrichAlbums(): Int {
        val pending = albumRepo.findByMetadataCheckedAtIsNull(batchSize, 0)
        var processed = 0
        for (album in pending) {
            try {
                enrichAlbum(album.entityId)
                processed++
            } catch (e: Exception) {
                log.warn("Album metadata enrichment failed for {}", album.entityId, e)
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
                album.musicbrainzId = representativeReleaseMbid(albumName, artistName, match.candidate.mbid)
                downloadCoverIfMissing(albumId, match.candidate.mbid)
            }
        }

        album.metadataCheckedAt = now()
        albumRepo.save(album)
    }

    private fun representativeReleaseMbid(
        albumName: String,
        artistName: String?,
        releaseGroupMbid: String,
    ): String? {
        val releases = musicBrainz.searchReleases(albumName, artistName)
        return releases.firstOrNull { it.releaseGroup?.id == releaseGroupMbid }?.id
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

        imageRepo.findByEntityIdAndIsPrimaryTrue(albumId)?.let { imageRepo.delete(it) }
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
