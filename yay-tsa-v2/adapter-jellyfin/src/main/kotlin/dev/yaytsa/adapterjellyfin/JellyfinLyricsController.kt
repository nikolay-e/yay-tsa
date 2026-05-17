package dev.yaytsa.adapterjellyfin

import com.fasterxml.jackson.annotation.JsonProperty
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/Lyrics")
class JellyfinLyricsController {
    @PostMapping("/{trackId}/fetch")
    fun fetchLyrics(
        @PathVariable trackId: String,
    ): ResponseEntity<LyricsFetchResponse> = ResponseEntity.ok(LyricsFetchResponse(trackId = trackId, lyrics = emptyList()))
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
