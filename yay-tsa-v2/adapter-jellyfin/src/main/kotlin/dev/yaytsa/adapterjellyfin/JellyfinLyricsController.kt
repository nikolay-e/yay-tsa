package dev.yaytsa.adapterjellyfin

import com.fasterxml.jackson.annotation.JsonProperty
import dev.yaytsa.application.library.LibraryQueries
import dev.yaytsa.shared.EntityId
import org.slf4j.LoggerFactory
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
) {
    @GetMapping("/{trackId}")
    fun getLyrics(
        @PathVariable trackId: String,
    ): ResponseEntity<LyricsFetchResponse> = ResponseEntity.ok(loadLyrics(trackId))

    @PostMapping("/{trackId}/fetch")
    fun fetchLyrics(
        @PathVariable trackId: String,
    ): ResponseEntity<LyricsFetchResponse> = ResponseEntity.ok(loadLyrics(trackId))

    private fun loadLyrics(trackId: String): LyricsFetchResponse {
        val filePath = libraryQueries.resolveTrackFilePath(EntityId(trackId))
        if (filePath == null) {
            return LyricsFetchResponse(trackId = trackId, lyrics = emptyList())
        }
        val lrc =
            findSidecarLrc(Path.of(filePath))
                ?: return LyricsFetchResponse(trackId = trackId, lyrics = emptyList())
        return try {
            val content = Files.readString(lrc)
            LyricsFetchResponse(trackId = trackId, lyrics = parseLrc(content))
        } catch (e: Exception) {
            log.warn("Failed to read LRC for track {} from {}: {}", trackId, lrc, e.message)
            LyricsFetchResponse(trackId = trackId, lyrics = emptyList())
        }
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

    /**
     * Parse a single LRC file. Each timed line looks like `[mm:ss.xx] text` or `[mm:ss] text`.
     * Multiple timestamps on one line (compact LRC) are expanded. ID3-like tags
     * (`[ar:...]`, `[ti:...]`) are ignored.
     *
     * `endMs` is set to the next line's `startMs` so the UI can highlight the active
     * line without separate logic. The last line's endMs is left null.
     */
    internal fun parseLrc(content: String): List<LyricLine> {
        val timed = mutableListOf<Pair<Long, String>>()
        val tsRegex = Regex("""\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?]""")
        for (raw in content.lineSequence()) {
            val matches = tsRegex.findAll(raw).toList()
            if (matches.isEmpty()) continue
            val text = raw.substring(matches.last().range.last + 1).trim()
            if (text.isEmpty() && matches.size == 1) {
                // header-only tag line like `[ar: Artist]` — skip
                continue
            }
            for (m in matches) {
                val (mm, ss, fracRaw) = m.destructured
                val frac = fracRaw.ifEmpty { "0" }
                val fracMs =
                    when (frac.length) {
                        1 -> frac.toLong() * 100
                        2 -> frac.toLong() * 10
                        else -> frac.padEnd(3, '0').take(3).toLong()
                    }
                val totalMs = mm.toLong() * 60_000 + ss.toLong() * 1000 + fracMs
                timed += totalMs to text
            }
        }
        if (timed.isEmpty()) return emptyList()
        val sorted = timed.sortedBy { it.first }
        return sorted.mapIndexed { i, (start, text) ->
            LyricLine(text = text, startMs = start, endMs = sorted.getOrNull(i + 1)?.first)
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(JellyfinLyricsController::class.java)
    }
}

data class LyricsFetchResponse(
    @JsonProperty("trackId") val trackId: String,
    @JsonProperty("lyrics") val lyrics: List<LyricLine>,
)

data class LyricLine(
    @JsonProperty("text") val text: String,
    @JsonProperty("startMs") val startMs: Long? = null,
    @JsonProperty("endMs") val endMs: Long? = null,
)
