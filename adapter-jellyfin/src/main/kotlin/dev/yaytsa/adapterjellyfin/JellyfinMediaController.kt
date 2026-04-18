package dev.yaytsa.adapterjellyfin

import dev.yaytsa.application.library.LibraryQueries
import dev.yaytsa.shared.EntityId
import org.springframework.core.io.FileSystemResource
import org.springframework.core.io.Resource
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.nio.file.Files
import java.nio.file.Path

@RestController
class JellyfinMediaController(
    private val libraryQueries: LibraryQueries,
) {
    @GetMapping("/Items/{itemId}/Images/{imageType}")
    fun getImage(
        @PathVariable itemId: String,
        @PathVariable imageType: String,
        @RequestParam(required = false) maxWidth: Int?,
        @RequestParam(required = false) maxHeight: Int?,
        @RequestParam(required = false) quality: Int?,
    ): ResponseEntity<Resource> {
        val image =
            libraryQueries.getPrimaryImage(EntityId(itemId))
                ?: return ResponseEntity.notFound().build()

        val filePath = Path.of(image.path)
        if (!Files.exists(filePath)) return ResponseEntity.notFound().build()

        val contentType =
            when (filePath.toString().substringAfterLast('.').lowercase()) {
                "jpg", "jpeg" -> "image/jpeg"
                "png" -> "image/png"
                "webp" -> "image/webp"
                else -> "image/jpeg"
            }

        return ResponseEntity
            .ok()
            .header(HttpHeaders.CONTENT_TYPE, contentType)
            .header(HttpHeaders.CACHE_CONTROL, "max-age=2592000")
            .body(FileSystemResource(filePath))
    }

    @GetMapping("/Audio/{itemId}/universal")
    fun universalAudio(
        @PathVariable itemId: String,
        @RequestParam(name = "api_key", required = false) apiKey: String?,
        @RequestParam(required = false) deviceId: String?,
        @RequestHeader(HttpHeaders.RANGE, required = false) range: String?,
    ): ResponseEntity<*> = streamAudio(itemId, apiKey, deviceId, range)

    @GetMapping("/Audio/{itemId}/stream")
    fun streamAudio(
        @PathVariable itemId: String,
        @RequestParam(name = "api_key", required = false) apiKey: String?,
        @RequestParam(required = false) deviceId: String?,
        @RequestHeader(HttpHeaders.RANGE, required = false) range: String?,
    ): ResponseEntity<*> {
        val track =
            libraryQueries.getTrack(EntityId(itemId))
                ?: return ResponseEntity.notFound().build<Any>()

        val sourcePath =
            libraryQueries.resolveTrackFilePath(EntityId(itemId))
                ?: return ResponseEntity.notFound().build<Any>()

        val filePath = Path.of(sourcePath)
        if (!Files.exists(filePath) || !Files.isRegularFile(filePath)) {
            return ResponseEntity.notFound().build<Any>()
        }

        val resource = FileSystemResource(filePath)
        val contentType =
            when (track.codec?.lowercase()) {
                "mp3" -> "audio/mpeg"
                "flac" -> "audio/flac"
                "ogg", "vorbis" -> "audio/ogg"
                "opus" -> "audio/opus"
                "aac", "m4a" -> "audio/mp4"
                "wav" -> "audio/wav"
                else -> "application/octet-stream"
            }
        val fileSize = Files.size(filePath)

        // Range support
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
