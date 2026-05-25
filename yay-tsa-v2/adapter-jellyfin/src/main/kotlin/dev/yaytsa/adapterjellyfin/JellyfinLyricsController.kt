package dev.yaytsa.adapterjellyfin

import com.fasterxml.jackson.annotation.JsonProperty
import dev.yaytsa.application.library.LibraryQueries
import dev.yaytsa.shared.EntityId
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.nio.file.Files
import java.nio.file.Path

@RestController
@RequestMapping("/Lyrics")
class JellyfinLyricsController(
    private val libraryQueries: LibraryQueries,
    private val lrclibClient: LrclibClient,
    @Value("\${yaytsa.lyrics.lrclib.enabled:true}") private val lrclibEnabled: Boolean,
) {
    @GetMapping("/{trackId}")
    fun getLyrics(
        @PathVariable trackId: String,
    ): ResponseEntity<LyricsFetchResponse> = ResponseEntity.ok(loadLyrics(trackId))

    @PostMapping("/{trackId}/fetch")
    fun fetchLyrics(
        @PathVariable trackId: String,
    ): ResponseEntity<LyricsFetchResponse> = ResponseEntity.ok(loadLyrics(trackId))

    // PWA parses LRC client-side (packages/core/src/lyrics/lrc-parser.ts), so we ship the
    // raw file content and let the browser handle timestamp parsing + line indexing.
    // Server-side parsed arrays leaked into the PWA earlier as `result.lyrics: LyricLine[]`
    // where the client expected `result.lyrics: string`, silently breaking every overlay.
    private fun loadLyrics(trackId: String): LyricsFetchResponse {
        val entityId = EntityId(trackId)
        val sidecar = readSidecar(trackId, entityId)

        // A time-synced sidecar wins outright (cheap, local, scrollable).
        if (sidecar != null && isSynced(sidecar)) {
            return LyricsFetchResponse(found = true, lyrics = sidecar, source = SOURCE_SIDECAR)
        }

        // Sidecar is plain or absent — a plain .lrc must not shadow a synced LRCLIB hit.
        if (lrclibEnabled) {
            val lrclib = fetchFromLrclib(entityId)
            if (lrclib != null && isSynced(lrclib.lyrics)) {
                return lrclib
            }
            if (sidecar != null) {
                return LyricsFetchResponse(found = true, lyrics = sidecar, source = SOURCE_SIDECAR)
            }
            if (lrclib != null) {
                return lrclib
            }
        } else if (sidecar != null) {
            return LyricsFetchResponse(found = true, lyrics = sidecar, source = SOURCE_SIDECAR)
        }
        return LyricsFetchResponse(found = false, lyrics = "", source = SOURCE_NONE)
    }

    private fun readSidecar(
        trackId: String,
        entityId: EntityId,
    ): String? {
        val filePath = libraryQueries.resolveTrackFilePath(entityId) ?: return null
        val lrc = findSidecarLrc(Path.of(filePath)) ?: return null
        return try {
            Files.readString(lrc)
        } catch (e: Exception) {
            log.warn("Failed to read LRC for track {} from {}: {}", trackId, lrc, e.message)
            null
        }
    }

    private fun isSynced(text: String): Boolean = SYNC_TIMESTAMP.containsMatchIn(text)

    private fun fetchFromLrclib(entityId: EntityId): LyricsFetchResponse? {
        val track = libraryQueries.getTrack(entityId) ?: return null
        val artistName = track.albumArtistId?.let { libraryQueries.getArtist(it)?.name }
        val albumName = track.albumId?.let { libraryQueries.getAlbum(it)?.name }
        val lyrics =
            lrclibClient.fetch(
                trackName = track.name,
                artistName = artistName,
                albumName = albumName,
                durationSeconds = track.durationMs?.div(1000),
            ) ?: return null
        return LyricsFetchResponse(found = true, lyrics = lyrics, source = SOURCE_LRCLIB)
    }

    /**
     * Look for an LRC file in `<album>/.lyrics/<basename>.lrc`.
     * Many libraries ship variants like `01 - Title.lrc` and `01 - Artist - Title.lrc`
     * sitting next to each other; pick the first match.
     */
    private fun findSidecarLrc(audioFile: Path): Path? {
        val parent = audioFile.parent ?: return null
        val lyricsDir = parent.resolve(".lyrics")
        if (!Files.isDirectory(lyricsDir)) return null

        val baseName = audioFile.fileName.toString().substringBeforeLast('.')
        // 1. Exact basename match
        val direct = lyricsDir.resolve("$baseName.lrc")
        if (Files.isRegularFile(direct)) return direct

        // 2. Loose match — any .lrc whose name starts with the track-number prefix.
        // Examples: file "01 - Song.flac" matches "01 - Song.lrc" OR "01 - Artist - Song.lrc".
        val numberPrefix = Regex("^(\\d{1,3})\\s*[-._]\\s*").find(baseName)?.value
        if (numberPrefix != null) {
            return Files
                .newDirectoryStream(lyricsDir, "*.lrc")
                .use { stream ->
                    stream.firstOrNull { p -> p.fileName.toString().startsWith(numberPrefix) }
                }
        }
        return null
    }

    companion object {
        private val log = LoggerFactory.getLogger(JellyfinLyricsController::class.java)
        private const val SOURCE_SIDECAR = "sidecar"
        private const val SOURCE_LRCLIB = "lrclib"
        private const val SOURCE_NONE = "none"
        private val SYNC_TIMESTAMP = Regex("""\[\d{1,2}:\d{2}""")
    }
}

data class LyricsFetchResponse(
    @JsonProperty("found") val found: Boolean,
    @JsonProperty("lyrics") val lyrics: String,
    @JsonProperty("source") val source: String,
)
