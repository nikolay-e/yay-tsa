package dev.yaytsa.adapterjellyfin

import com.fasterxml.jackson.annotation.JsonProperty
import dev.yaytsa.adaptershared.LyricsResolver
import dev.yaytsa.adaptershared.LyricsSource
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/Lyrics")
class JellyfinLyricsController(
    private val lyricsResolver: LyricsResolver,
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
    private fun loadLyrics(trackId: String): LyricsFetchResponse {
        val resolved = lyricsResolver.resolve(trackId)
        return when (resolved.source) {
            LyricsSource.NONE -> LyricsFetchResponse(found = false, lyrics = "", source = SOURCE_NONE)
            LyricsSource.SIDECAR -> LyricsFetchResponse(found = true, lyrics = resolved.raw, source = SOURCE_SIDECAR)
            LyricsSource.LRCLIB -> LyricsFetchResponse(found = true, lyrics = resolved.raw, source = SOURCE_LRCLIB)
        }
    }

    companion object {
        private const val SOURCE_SIDECAR = "sidecar"
        private const val SOURCE_LRCLIB = "lrclib"
        private const val SOURCE_NONE = "none"
    }
}

data class LyricsFetchResponse(
    @JsonProperty("found") val found: Boolean,
    @JsonProperty("lyrics") val lyrics: String,
    @JsonProperty("source") val source: String,
)
