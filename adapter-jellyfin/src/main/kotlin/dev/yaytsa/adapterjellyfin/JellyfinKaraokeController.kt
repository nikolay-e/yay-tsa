package dev.yaytsa.adapterjellyfin

import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/Karaoke")
class JellyfinKaraokeController {
    @GetMapping("/enabled")
    fun isEnabled(): ResponseEntity<Any> = ResponseEntity.ok(mapOf("enabled" to true))

    @GetMapping("/{trackId}/status")
    fun getStatus(
        @PathVariable trackId: String,
    ): ResponseEntity<Any> = ResponseEntity.ok(mapOf("state" to "NOT_STARTED", "message" to null))

    @PostMapping("/{trackId}/process")
    fun process(
        @PathVariable trackId: String,
    ): ResponseEntity<Any> = ResponseEntity.ok(mapOf("state" to "PROCESSING", "message" to "Queued for processing"))

    @GetMapping("/{trackId}/instrumental")
    fun getInstrumental(
        @PathVariable trackId: String,
        @RequestParam(name = "api_key", required = false) apiKey: String?,
    ): ResponseEntity<Any> = ResponseEntity.status(HttpStatus.NOT_FOUND).body(mapOf("error" to "Karaoke stems not yet available"))

    @GetMapping("/{trackId}/vocals")
    fun getVocals(
        @PathVariable trackId: String,
        @RequestParam(name = "api_key", required = false) apiKey: String?,
    ): ResponseEntity<Any> = ResponseEntity.status(HttpStatus.NOT_FOUND).body(mapOf("error" to "Karaoke stems not yet available"))
}
