package dev.yaytsa.adaptershared

import dev.yaytsa.application.library.LibraryQueries
import dev.yaytsa.shared.EntityId
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.Path

enum class LyricsSource { SIDECAR, LRCLIB, NONE }

data class ResolvedLyrics(
    val raw: String,
    val source: LyricsSource,
    val synced: Boolean,
    val displayArtist: String?,
    val displayTitle: String?,
) {
    companion object {
        val EMPTY = ResolvedLyrics(raw = "", source = LyricsSource.NONE, synced = false, displayArtist = null, displayTitle = null)
    }
}

data class LyricsLine(
    val start: Long?,
    val value: String,
)

@Component
class LyricsResolver(
    private val libraryQueries: LibraryQueries,
    private val lrclibClient: LrclibClient,
    @Value("\${yaytsa.lyrics.lrclib.enabled:true}") private val lrclibEnabled: Boolean,
) {
    fun resolve(trackId: String): ResolvedLyrics {
        val entityId = EntityId(trackId)
        val displayArtist = trackDisplayArtist(entityId)
        val displayTitle = libraryQueries.getTrack(entityId)?.name
        val sidecar = readSidecar(trackId, entityId)

        if (sidecar != null && isSynced(sidecar)) {
            return resolved(sidecar, LyricsSource.SIDECAR, displayArtist, displayTitle)
        }

        if (lrclibEnabled) {
            val lrclib = fetchFromLrclib(entityId)
            if (lrclib != null && isSynced(lrclib)) {
                return resolved(lrclib, LyricsSource.LRCLIB, displayArtist, displayTitle)
            }
            if (sidecar != null) return resolved(sidecar, LyricsSource.SIDECAR, displayArtist, displayTitle)
            if (lrclib != null) return resolved(lrclib, LyricsSource.LRCLIB, displayArtist, displayTitle)
        } else if (sidecar != null) {
            return resolved(sidecar, LyricsSource.SIDECAR, displayArtist, displayTitle)
        }
        return ResolvedLyrics.EMPTY.copy(displayArtist = displayArtist, displayTitle = displayTitle)
    }

    fun parseLines(lyrics: ResolvedLyrics): List<LyricsLine> {
        if (lyrics.raw.isBlank()) return emptyList()
        if (!lyrics.synced) {
            return lyrics.raw
                .lineSequence()
                .map { LyricsLine(start = null, value = it.trim()) }
                .toList()
        }
        return lyrics.raw
            .lineSequence()
            .mapNotNull { parseSyncedLine(it) }
            .toList()
    }

    private fun parseSyncedLine(line: String): LyricsLine? {
        val matches = TIMESTAMP.findAll(line).toList()
        if (matches.isEmpty()) return null
        val first = matches.first()
        val minutes = first.groupValues[1].toLong()
        val seconds = first.groupValues[2].toLong()
        val fraction = first.groupValues[3]
        val fractionMs =
            when (fraction.length) {
                0 -> 0L
                1 -> fraction.toLong() * 100
                2 -> fraction.toLong() * 10
                else -> fraction.take(3).toLong()
            }
        val start = (minutes * 60 + seconds) * 1000 + fractionMs
        val text = line.substring(matches.last().range.last + 1).trim()
        return LyricsLine(start = start, value = text)
    }

    private fun resolved(
        raw: String,
        source: LyricsSource,
        displayArtist: String?,
        displayTitle: String?,
    ): ResolvedLyrics = ResolvedLyrics(raw = raw, source = source, synced = isSynced(raw), displayArtist = displayArtist, displayTitle = displayTitle)

    private fun trackDisplayArtist(entityId: EntityId): String? {
        val track = libraryQueries.getTrack(entityId) ?: return null
        return track.albumArtistId?.let { libraryQueries.getArtist(it)?.name }
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

    private fun fetchFromLrclib(entityId: EntityId): String? {
        val track = libraryQueries.getTrack(entityId) ?: return null
        val artistName = track.albumArtistId?.let { libraryQueries.getArtist(it)?.name }
        val albumName = track.albumId?.let { libraryQueries.getAlbum(it)?.name }
        return lrclibClient.fetch(
            trackName = track.name,
            artistName = artistName,
            albumName = albumName,
            durationSeconds = track.durationMs?.div(1000),
        )
    }

    private fun findSidecarLrc(audioFile: Path): Path? {
        val parent = audioFile.parent ?: return null
        val lyricsDir = parent.resolve(".lyrics")
        if (!Files.isDirectory(lyricsDir)) return null

        val baseName = audioFile.fileName.toString().substringBeforeLast('.')
        val direct = lyricsDir.resolve("$baseName.lrc")
        if (Files.isRegularFile(direct)) return direct

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
        private val log = LoggerFactory.getLogger(LyricsResolver::class.java)
        private val SYNC_TIMESTAMP = Regex("""\[\d{1,2}:\d{2}""")
        private val TIMESTAMP = Regex("""\[(\d{1,2}):(\d{2})(?:[.:](\d{1,3}))?]""")
    }
}
