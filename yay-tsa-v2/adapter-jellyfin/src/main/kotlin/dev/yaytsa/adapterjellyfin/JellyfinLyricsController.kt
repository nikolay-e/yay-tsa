package dev.yaytsa.adapterjellyfin

import org.springframework.http.HttpStatus
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
    ): ResponseEntity<Any> =
        ResponseEntity
            .status(HttpStatus.NOT_IMPLEMENTED)
            .body(
                mapOf(
                    "status" to "not_implemented",
                    "trackId" to trackId,
                    "message" to "Lyrics fetch pipeline not yet wired",
                ),
            )
}
