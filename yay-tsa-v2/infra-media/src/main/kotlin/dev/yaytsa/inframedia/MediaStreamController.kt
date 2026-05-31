package dev.yaytsa.inframedia

import dev.yaytsa.application.library.LibraryQueries
import dev.yaytsa.shared.EntityId
import jakarta.servlet.http.HttpServletResponse
import org.springframework.beans.factory.annotation.Value
import org.springframework.core.io.FileSystemResource
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
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
    @Value("\${yaytsa.library.music-path:#{null}}") musicPath: String?,
) {
    private val safeRoot = MediaPathSafety.resolveRoot(musicPath)

    // Stream bytes straight to the response: a `ResponseEntity<*>` body of ResourceRegion erases
    // to Object at runtime and ResourceRegionHttpMessageConverter then can't match it ->
    // HttpMessageNotWritableException -> 500 (same trap documented in JellyfinKaraokeController).
    @GetMapping("/rest/stream", "/rest/stream.view")
    fun subsonicStream(
        @RequestParam id: String,
        @RequestHeader(HttpHeaders.RANGE, required = false) range: String?,
        response: HttpServletResponse,
    ) {
        val filePath = resolveTrackFile(id, response) ?: return
        val track = libraryQueries.getTrack(EntityId(id))!!
        streamFile(response, filePath, resolveContentType(track.codec), range)
    }

    @GetMapping("/rest/download", "/rest/download.view")
    fun subsonicDownload(
        @RequestParam id: String,
    ): ResponseEntity<*> = serveTrack(id, download = true)

    @GetMapping("/rest/getCoverArt", "/rest/getCoverArt.view")
    fun subsonicCoverArt(
        @RequestParam id: String,
    ): ResponseEntity<*> {
        val image =
            libraryQueries.getPrimaryImage(EntityId(id))
                ?: return ResponseEntity.notFound().build<Any>()
        val filePath =
            MediaPathSafety.resolveServableFile(Path.of(image.path), safeRoot)
                ?: return ResponseEntity.notFound().build<Any>()
        val contentType =
            when (filePath.toString().substringAfterLast('.').lowercase()) {
                "png" -> "image/png"
                "webp" -> "image/webp"
                "gif" -> "image/gif"
                else -> "image/jpeg"
            }
        return ResponseEntity
            .ok()
            .header(HttpHeaders.CONTENT_TYPE, contentType)
            .header(HttpHeaders.CACHE_CONTROL, "max-age=2592000")
            .body(FileSystemResource(filePath))
    }

    private fun resolveTrackFile(
        trackId: String,
        response: HttpServletResponse,
    ): Path? {
        val entityId = EntityId(trackId)
        if (libraryQueries.getTrack(entityId) == null) {
            response.sendError(HttpStatus.NOT_FOUND.value())
            return null
        }
        val sourcePath = libraryQueries.resolveTrackFilePath(entityId)
        val filePath = sourcePath?.let { MediaPathSafety.resolveServableFile(Path.of(it), safeRoot) }
        if (filePath == null) {
            response.sendError(HttpStatus.NOT_FOUND.value())
            return null
        }
        return filePath
    }

    private fun streamFile(
        response: HttpServletResponse,
        filePath: Path,
        contentType: String,
        rangeHeader: String?,
    ) {
        val fileSize = Files.size(filePath)
        response.setHeader(HttpHeaders.CONTENT_TYPE, contentType)
        response.setHeader(HttpHeaders.ACCEPT_RANGES, "bytes")

        if (rangeHeader != null && rangeHeader.startsWith("bytes=") && !rangeHeader.contains(",")) {
            val parts = rangeHeader.removePrefix("bytes=").split("-")
            val suffixSpec = parts.size == 2 && parts[0].isEmpty()
            val start: Long
            val requestedEnd: Long
            if (suffixSpec) {
                start = (fileSize - (parts[1].toLongOrNull() ?: 0L)).coerceAtLeast(0L)
                requestedEnd = fileSize - 1
            } else {
                start = parts[0].toLongOrNull() ?: 0L
                requestedEnd = if (parts.size > 1 && parts[1].isNotEmpty()) parts[1].toLongOrNull() ?: (fileSize - 1) else fileSize - 1
            }
            // RFC 7233: 416 only when first-byte-pos is unsatisfiable; an over-long end is clamped.
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

    private fun serveTrack(
        trackId: String,
        download: Boolean = false,
    ): ResponseEntity<*> {
        val entityId = EntityId(trackId)
        val track =
            libraryQueries.getTrack(entityId)
                ?: return ResponseEntity.notFound().build<Any>()
        val sourcePath =
            libraryQueries.resolveTrackFilePath(entityId)
                ?: return ResponseEntity.notFound().build<Any>()
        val filePath =
            MediaPathSafety.resolveServableFile(Path.of(sourcePath), safeRoot)
                ?: return ResponseEntity.notFound().build<Any>()
        val resource = FileSystemResource(filePath)
        val contentType = resolveContentType(track.codec)
        val fileSize = Files.size(filePath)
        val fileName = filePath.fileName?.toString() ?: track.name
        return ResponseEntity
            .ok()
            .header(HttpHeaders.CONTENT_TYPE, contentType)
            .header(HttpHeaders.CONTENT_LENGTH, fileSize.toString())
            .apply { if (download) header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"$fileName\"") }
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
