package dev.yaytsa.inframedia

import dev.yaytsa.application.library.LibraryQueries
import dev.yaytsa.shared.EntityId
import org.springframework.core.io.FileSystemResource
import org.springframework.http.HttpHeaders
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.nio.file.Files
import java.nio.file.Path

@RestController
class MediaStreamController(
    private val libraryQueries: LibraryQueries,
) {
    @GetMapping("/rest/stream", "/rest/stream.view")
    fun subsonicStream(
        @RequestParam id: String,
        @RequestHeader(HttpHeaders.RANGE, required = false) range: String?,
    ): ResponseEntity<*> = serveTrack(id, rangeHeader = range)

    @GetMapping("/rest/download", "/rest/download.view")
    fun subsonicDownload(
        @RequestParam id: String,
    ): ResponseEntity<*> = serveTrack(id, download = true)

    private fun serveTrack(
        trackId: String,
        download: Boolean = false,
        rangeHeader: String? = null,
    ): ResponseEntity<*> {
        val entityId = EntityId(trackId)

        val track =
            libraryQueries.getTrack(entityId)
                ?: return ResponseEntity.notFound().build<Any>()

        val sourcePath =
            libraryQueries.resolveTrackFilePath(entityId)
                ?: return ResponseEntity.notFound().build<Any>()

        val filePath = Path.of(sourcePath)
        if (!Files.exists(filePath) || !Files.isRegularFile(filePath)) {
            return ResponseEntity.notFound().build<Any>()
        }

        val resource = FileSystemResource(filePath)
        val contentType = resolveContentType(track.codec)
        val fileSize = Files.size(filePath)

        if (download) {
            val fileName = filePath.fileName?.toString() ?: track.name
            return ResponseEntity
                .ok()
                .header(HttpHeaders.CONTENT_TYPE, contentType)
                .header(HttpHeaders.CONTENT_LENGTH, fileSize.toString())
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"$fileName\"")
                .body(resource)
        }

        // Handle Range requests
        if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
            val rangeSpec = rangeHeader.removePrefix("bytes=")
            val parts = rangeSpec.split("-")
            val start = parts[0].toLongOrNull() ?: 0L
            val end = if (parts.size > 1 && parts[1].isNotEmpty()) parts[1].toLongOrNull() ?: (fileSize - 1) else fileSize - 1
            val contentLength = end - start + 1

            return ResponseEntity
                .status(org.springframework.http.HttpStatus.PARTIAL_CONTENT)
                .header(HttpHeaders.CONTENT_TYPE, contentType)
                .header(HttpHeaders.CONTENT_LENGTH, contentLength.toString())
                .header(HttpHeaders.CONTENT_RANGE, "bytes $start-$end/$fileSize")
                .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                .body(
                    org.springframework.core.io.support
                        .ResourceRegion(resource, start, contentLength),
                )
        }

        return ResponseEntity
            .ok()
            .header(HttpHeaders.CONTENT_TYPE, contentType)
            .header(HttpHeaders.CONTENT_LENGTH, fileSize.toString())
            .header(HttpHeaders.ACCEPT_RANGES, "bytes")
            .body(resource)
    }

    private fun resolveContentType(codec: String?): String =
        when (codec?.lowercase()) {
            "mp3" -> "audio/mpeg"
            "flac" -> "audio/flac"
            "ogg", "vorbis" -> "audio/ogg"
            "opus" -> "audio/opus"
            "aac", "m4a" -> "audio/mp4"
            "wav" -> "audio/wav"
            "alac" -> "audio/mp4"
            "wma" -> "audio/x-ms-wma"
            "ape" -> "audio/x-ape"
            "dsf", "dsd" -> "audio/x-dsf"
            else -> "application/octet-stream"
        }
}
