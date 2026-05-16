package dev.yaytsa.adapterjellyfin

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
    ): ResponseEntity<Map<String, Any>> =
        ResponseEntity.ok(
            mapOf(
                "trackId" to trackId,
                "lyrics" to emptyList<Map<String, Any>>(),
            ),
        )
}
