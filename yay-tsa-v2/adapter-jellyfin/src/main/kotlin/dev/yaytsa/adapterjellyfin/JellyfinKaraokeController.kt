package dev.yaytsa.adapterjellyfin

import dev.yaytsa.application.karaoke.port.KaraokeQueryPort
import dev.yaytsa.shared.TrackId
import dev.yaytsa.shared.generated.KaraokeState
import org.springframework.beans.factory.annotation.Value
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
    @Value("\${yaytsa.karaoke.output-path:#{null}}") karaokeOutputPath: String?,
    @Value("\${yaytsa.karaoke.enabled:false}") private val karaokeEnabled: Boolean,
    @Value("\${yaytsa.karaoke.fail-threshold:3}") private val failThreshold: Int,
) {
    private val safeRoot = MediaPathSafety.resolveRoot(karaokeOutputPath)

    @GetMapping("/enabled")
    fun isEnabled(): ResponseEntity<Any> = ResponseEntity.ok(mapOf("enabled" to karaokeEnabled))

    private fun stateOf(asset: dev.yaytsa.domain.karaoke.KaraokeAsset?): KaraokeState =
        when {
            asset == null -> KaraokeState.NOT_STARTED
            asset.readyAt != null -> KaraokeState.READY
            asset.failCount >= failThreshold -> KaraokeState.FAILED
            else -> KaraokeState.PROCESSING
        }

    @GetMapping("/{trackId}/status")
    fun getStatus(
        @PathVariable trackId: String,
    ): ResponseEntity<Any> {
        val asset = karaokeQueryPort.getAsset(TrackId(trackId))
        val state = stateOf(asset)
        val message = if (state == KaraokeState.FAILED) asset?.lastError else null
        return ResponseEntity.ok(mapOf("state" to state.name, "message" to message))
    }

    @PostMapping("/{trackId}/process")
    fun process(
        @PathVariable trackId: String,
    ): ResponseEntity<Any> {
        if (!karaokeEnabled) {
            return ResponseEntity
                .status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(mapOf("state" to stateOf(karaokeQueryPort.getAsset(TrackId(trackId))).name, "message" to "Karaoke processing is disabled"))
        }
        val asset = karaokeQueryPort.getAsset(TrackId(trackId))
        if (asset?.readyAt != null) {
            return ResponseEntity.ok(mapOf("state" to KaraokeState.READY.name, "message" to null))
        }
        karaokeQueryPort.requeueFailed(TrackId(trackId))
        return ResponseEntity.ok(mapOf("state" to KaraokeState.PROCESSING.name, "message" to "Queued for processing"))
    }

    @GetMapping("/{trackId}/instrumental")
    fun getInstrumental(
        @PathVariable trackId: String,
        @RequestParam(name = "api_key", required = false) apiKey: String?,
        @RequestHeader(HttpHeaders.RANGE, required = false) range: String?,
        response: jakarta.servlet.http.HttpServletResponse,
    ) = streamStem(trackId, range, response) { it.instrumentalPath }

    @GetMapping("/{trackId}/vocals")
    fun getVocals(
        @PathVariable trackId: String,
        @RequestParam(name = "api_key", required = false) apiKey: String?,
        @RequestHeader(HttpHeaders.RANGE, required = false) range: String?,
        response: jakarta.servlet.http.HttpServletResponse,
    ) = streamStem(trackId, range, response) { it.vocalPath }

    @GetMapping("/{trackId}/status/stream", produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
    fun statusStream(
        @PathVariable trackId: String,
    ): SseEmitter {
        val emitter = SseEmitter(30_000L)
        val state = stateOf(karaokeQueryPort.getAsset(TrackId(trackId)))
        try {
            emitter.send(SseEmitter.event().name("status").data(mapOf("state" to state.name)))
            emitter.complete()
        } catch (e: Exception) {
            emitter.completeWithError(e)
        }
        return emitter
    }

    /**
     * Stream a karaoke stem directly to [response]. We bypass Spring's
     * message-converter pipeline because `ResponseEntity<*>` erases to
     * `ResponseEntity<Any>` at runtime — `ResourceRegionHttpMessageConverter`
     * then can't match against `Object`, throwing
     * `HttpMessageNotWritableException: No converter for ResourceRegion`
     * even when Content-Type is correct (same trap that bit `/Audio/stream`
     * during the v1→v2 cutover — see QA.md). Writing bytes directly avoids
     * the trap entirely.
     */
    private fun streamStem(
        trackId: String,
        range: String?,
        response: jakarta.servlet.http.HttpServletResponse,
        pathSelector: (dev.yaytsa.domain.karaoke.KaraokeAsset) -> String?,
    ) {
        val asset = karaokeQueryPort.getAsset(TrackId(trackId))
        if (asset == null) {
            response.sendError(HttpStatus.NOT_FOUND.value(), "Karaoke asset not found")
            return
        }
        val stemPath = pathSelector(asset)
        if (stemPath == null) {
            response.sendError(HttpStatus.NOT_FOUND.value(), "Stem not available")
            return
        }
        val filePath = MediaPathSafety.resolveServableFile(Path.of(stemPath), safeRoot)
        if (filePath == null) {
            response.sendError(HttpStatus.NOT_FOUND.value(), "Stem file missing on disk")
            return
        }

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

        response.setHeader(HttpHeaders.CONTENT_TYPE, contentType)
        response.setHeader(HttpHeaders.ACCEPT_RANGES, "bytes")

        if (range != null && range.startsWith("bytes=")) {
            val rangeSpec = range.removePrefix("bytes=")
            val parts = rangeSpec.split("-")
            val suffixSpec = parts.size == 2 && parts[0].isEmpty()
            val start: Long
            val requestedEnd: Long
            if (suffixSpec) {
                val suffixLength = parts[1].toLongOrNull() ?: 0L
                start = (fileSize - suffixLength).coerceAtLeast(0L)
                requestedEnd = fileSize - 1
            } else {
                start = parts[0].toLongOrNull() ?: 0L
                requestedEnd =
                    if (parts.size > 1 && parts[1].isNotEmpty()) {
                        parts[1].toLongOrNull() ?: (fileSize - 1)
                    } else {
                        fileSize - 1
                    }
            }
            // RFC 7233 §2.1: a last-byte-pos past EOF is clamped, not rejected. 416 is only
            // for an unsatisfiable first-byte-pos (start beyond the resource).
            if (start < 0 || start >= fileSize || requestedEnd < start) {
                response.setHeader(HttpHeaders.CONTENT_RANGE, "bytes */$fileSize")
                response.sendError(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE.value())
                return
            }
            val end = minOf(requestedEnd, fileSize - 1)
            val length = end - start + 1
            response.status = HttpStatus.PARTIAL_CONTENT.value()
            response.setHeader(HttpHeaders.CONTENT_LENGTH, length.toString())
            response.setHeader(HttpHeaders.CONTENT_RANGE, "bytes $start-$end/$fileSize")
            java.io.RandomAccessFile(filePath.toFile(), "r").use { raf ->
                raf.seek(start)
                val out = response.outputStream
                val buf = ByteArray(64 * 1024)
                var remaining = length
                while (remaining > 0) {
                    val read = raf.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
                    if (read < 0) break
                    try {
                        out.write(buf, 0, read)
                    } catch (_: java.io.IOException) {
                        // Client disconnected mid-stream (pause/skip) — common, debug-level.
                        return
                    }
                    remaining -= read
                }
            }
            return
        }

        response.status = HttpStatus.OK.value()
        response.setHeader(HttpHeaders.CONTENT_LENGTH, fileSize.toString())
        Files.newInputStream(filePath).use { input ->
            val out = response.outputStream
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                try {
                    out.write(buf, 0, n)
                } catch (_: java.io.IOException) {
                    return
                }
            }
        }
    }
}
