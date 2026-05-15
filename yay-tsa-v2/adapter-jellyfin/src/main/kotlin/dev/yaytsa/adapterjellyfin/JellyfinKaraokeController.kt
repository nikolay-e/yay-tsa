package dev.yaytsa.adapterjellyfin

import dev.yaytsa.application.karaoke.port.KaraokeQueryPort
import dev.yaytsa.shared.TrackId
import org.springframework.core.io.FileSystemResource
import org.springframework.core.io.Resource
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.nio.file.Files
import java.nio.file.Path

@RestController
@RequestMapping("/Karaoke")
class JellyfinKaraokeController(
    private val karaokeQueryPort: KaraokeQueryPort,
) {
    @GetMapping("/enabled")
    fun isEnabled(): ResponseEntity<Any> = ResponseEntity.ok(mapOf("enabled" to true))

    @GetMapping("/{trackId}/status")
    fun getStatus(
        @PathVariable trackId: String,
    ): ResponseEntity<Any> {
        val asset = karaokeQueryPort.getAsset(TrackId(trackId))
        val state =
            when {
                asset == null -> "NOT_STARTED"
                asset.readyAt != null -> "READY"
                else -> "PROCESSING"
            }
        return ResponseEntity.ok(mapOf("state" to state, "message" to null))
    }

    @PostMapping("/{trackId}/process")
    fun process(
        @PathVariable trackId: String,
    ): ResponseEntity<Any> = ResponseEntity.ok(mapOf("state" to "PROCESSING", "message" to "Queued for processing"))

    @GetMapping("/{trackId}/instrumental")
    fun getInstrumental(
        @PathVariable trackId: String,
        @RequestParam(name = "api_key", required = false) apiKey: String?,
        @RequestHeader(HttpHeaders.RANGE, required = false) range: String?,
    ): ResponseEntity<*> = streamStem(trackId, range) { it.instrumentalPath }

    @GetMapping("/{trackId}/vocals")
    fun getVocals(
        @PathVariable trackId: String,
        @RequestParam(name = "api_key", required = false) apiKey: String?,
        @RequestHeader(HttpHeaders.RANGE, required = false) range: String?,
    ): ResponseEntity<*> = streamStem(trackId, range) { it.vocalPath }

    @GetMapping("/{trackId}/status/stream", produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
    fun statusStream(
        @PathVariable trackId: String,
    ): SseEmitter {
        val emitter = SseEmitter(30_000L)
        val asset = karaokeQueryPort.getAsset(TrackId(trackId))
        val state =
            when {
                asset == null -> "NOT_STARTED"
                asset.readyAt != null -> "READY"
                else -> "PROCESSING"
            }
        try {
            emitter.send(SseEmitter.event().name("status").data(mapOf("state" to state)))
            emitter.complete()
        } catch (e: Exception) {
            emitter.completeWithError(e)
        }
        return emitter
    }

    private fun streamStem(
        trackId: String,
        range: String?,
        pathSelector: (dev.yaytsa.domain.karaoke.KaraokeAsset) -> String?,
    ): ResponseEntity<*> {
        val asset =
            karaokeQueryPort.getAsset(TrackId(trackId))
                ?: return ResponseEntity.status(HttpStatus.NOT_FOUND).body(mapOf("error" to "Karaoke asset not found"))
        val stemPath =
            pathSelector(asset)
                ?: return ResponseEntity.status(HttpStatus.NOT_FOUND).body(mapOf("error" to "Stem not available"))

        val filePath = Path.of(stemPath)
        if (!Files.exists(filePath) || !Files.isRegularFile(filePath)) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(mapOf("error" to "Stem file missing on disk"))
        }

        val resource: Resource = FileSystemResource(filePath)
        val contentType =
            when (filePath.toString().substringAfterLast('.').lowercase()) {
                "mp3" -> "audio/mpeg"
                "flac" -> "audio/flac"
                "ogg", "vorbis" -> "audio/ogg"
                "opus" -> "audio/opus"
                "m4a", "aac" -> "audio/mp4"
                "wav" -> "audio/wav"
                else -> "application/octet-stream"
            }
        val fileSize = Files.size(filePath)

        if (range != null && range.startsWith("bytes=")) {
            val rangeSpec = range.removePrefix("bytes=")
            val parts = rangeSpec.split("-")
            val start = parts[0].toLongOrNull() ?: 0L
            val end = if (parts.size > 1 && parts[1].isNotEmpty()) parts[1].toLongOrNull() ?: (fileSize - 1) else fileSize - 1
            val length = end - start + 1

            return ResponseEntity
                .status(HttpStatus.PARTIAL_CONTENT)
                .header(HttpHeaders.CONTENT_TYPE, contentType)
                .header(HttpHeaders.CONTENT_LENGTH, length.toString())
                .header(HttpHeaders.CONTENT_RANGE, "bytes $start-$end/$fileSize")
                .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                .body(
                    org.springframework.core.io.support
                        .ResourceRegion(resource, start, length),
                )
        }

        return ResponseEntity
            .ok()
            .header(HttpHeaders.CONTENT_TYPE, contentType)
            .header(HttpHeaders.CONTENT_LENGTH, fileSize.toString())
            .header(HttpHeaders.ACCEPT_RANGES, "bytes")
            .body(resource)
    }
}
