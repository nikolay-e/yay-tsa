package dev.yaytsa.adapterjellyfin

import dev.yaytsa.application.library.LibraryQueries
import dev.yaytsa.shared.EntityId
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
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
import java.io.IOException
import java.nio.channels.Channels
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

@RestController
class JellyfinMediaController(
    private val libraryQueries: LibraryQueries,
    private val thumbnails: ThumbnailService,
    @Value("\${yaytsa.library.music-path:#{null}}") musicPath: String?,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val safeRoot = MediaPathSafety.resolveRoot(musicPath)

    @GetMapping("/Items/{itemId}/Images/{imageType}")
    fun getImage(
        @PathVariable itemId: String,
        @PathVariable imageType: String,
        @RequestParam(required = false) maxWidth: Int?,
        @RequestParam(required = false) maxHeight: Int?,
        @RequestParam(required = false) quality: Int?,
        @RequestHeader(value = HttpHeaders.ACCEPT, required = false) accept: String?,
        @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) ifNoneMatch: String?,
    ): ResponseEntity<Resource> {
        val image =
            libraryQueries.getPrimaryImage(EntityId(itemId))
                ?: return ResponseEntity.notFound().build()

        val filePath =
            MediaPathSafety.resolveServableFile(Path.of(image.path), safeRoot)
                ?: return ResponseEntity.notFound().build()

        val acceptWebp = accept?.contains("image/webp", ignoreCase = true) == true
        val rendered = thumbnails.render(filePath, maxWidth, maxHeight, quality, acceptWebp)

        if (ifNoneMatch != null && ifNoneMatch == rendered.etag) {
            return ResponseEntity
                .status(HttpStatus.NOT_MODIFIED)
                .header(HttpHeaders.ETAG, rendered.etag)
                .header(HttpHeaders.CACHE_CONTROL, "max-age=2592000")
                .header(HttpHeaders.VARY, HttpHeaders.ACCEPT)
                .build()
        }

        return ResponseEntity
            .ok()
            .header(HttpHeaders.CONTENT_TYPE, rendered.contentType)
            .header(HttpHeaders.CACHE_CONTROL, "max-age=2592000")
            .header(HttpHeaders.ETAG, rendered.etag)
            .header(HttpHeaders.VARY, HttpHeaders.ACCEPT)
            .body(FileSystemResource(rendered.file))
    }

    @GetMapping("/Audio/{itemId}/universal")
    fun universalAudio(
        @PathVariable itemId: String,
        @RequestParam(name = "api_key", required = false) apiKey: String?,
        @RequestParam(required = false) deviceId: String?,
        @RequestHeader(HttpHeaders.RANGE, required = false) range: String?,
        response: HttpServletResponse,
    ) {
        streamAudio(itemId, apiKey, deviceId, range, response)
    }

    @GetMapping("/Audio/{itemId}/stream")
    fun streamAudio(
        @PathVariable itemId: String,
        @RequestParam(name = "api_key", required = false) apiKey: String?,
        @RequestParam(required = false) deviceId: String?,
        @RequestHeader(HttpHeaders.RANGE, required = false) range: String?,
        response: HttpServletResponse,
    ) {
        val track = libraryQueries.getTrack(EntityId(itemId))
        if (track == null) {
            response.status = 404
            return
        }
        val sourcePath = libraryQueries.resolveTrackFilePath(EntityId(itemId))
        if (sourcePath == null) {
            response.status = 404
            return
        }
        val requested = Path.of(sourcePath)
        val filePath = MediaPathSafety.resolveServableFile(requested, safeRoot)
        if (filePath == null) {
            response.status = 404
            return
        }

        val contentType = resolveAudioContentType(track.codec, filePath)
        val fileSize = Files.size(filePath)

        if (range != null && range.startsWith("bytes=")) {
            val rangeSpec = range.removePrefix("bytes=")
            val parts = rangeSpec.split("-")
            val suffixSpec = parts.size == 2 && parts[0].isEmpty()
            val start: Long
            val end: Long
            if (suffixSpec) {
                val suffixLength = parts[1].toLongOrNull() ?: 0L
                start = (fileSize - suffixLength).coerceAtLeast(0L)
                end = fileSize - 1
            } else {
                start = parts[0].toLongOrNull() ?: 0L
                end =
                    if (parts.size > 1 && parts[1].isNotEmpty()) {
                        parts[1].toLongOrNull() ?: (fileSize - 1)
                    } else {
                        fileSize - 1
                    }
            }
            if (start < 0 || start >= fileSize || end < start) {
                response.status = 416
                response.setHeader(HttpHeaders.CONTENT_RANGE, "bytes */$fileSize")
                return
            }
            val length = end - start + 1
            response.status = 206
            response.contentType = contentType
            response.setHeader(HttpHeaders.CONTENT_LENGTH, length.toString())
            response.setHeader(HttpHeaders.CONTENT_RANGE, "bytes $start-$end/$fileSize")
            response.setHeader(HttpHeaders.ACCEPT_RANGES, "bytes")
            writeRangeQuietly(filePath, start, length, response)
            return
        }

        response.status = 200
        response.contentType = contentType
        response.setHeader(HttpHeaders.CONTENT_LENGTH, fileSize.toString())
        response.setHeader(HttpHeaders.ACCEPT_RANGES, "bytes")
        writeFullFileQuietly(filePath, response)
    }

    private fun writeRangeQuietly(
        filePath: Path,
        start: Long,
        length: Long,
        response: HttpServletResponse,
    ) {
        try {
            FileChannel.open(filePath, StandardOpenOption.READ).use { channel ->
                channel.position(start)
                Channels.newInputStream(channel).use { input ->
                    val out = response.outputStream
                    val buffer = ByteArray(64 * 1024)
                    var remaining = length
                    while (remaining > 0) {
                        val toRead = minOf(buffer.size.toLong(), remaining).toInt()
                        val read = input.read(buffer, 0, toRead)
                        if (read == -1) break
                        out.write(buffer, 0, read)
                        remaining -= read
                    }
                    out.flush()
                }
            }
        } catch (e: IOException) {
            log.debug("Audio range stream aborted by client for {}: {}", filePath, e.message)
        }
    }

    private fun writeFullFileQuietly(
        filePath: Path,
        response: HttpServletResponse,
    ) {
        try {
            Files.newInputStream(filePath).use { input ->
                input.copyTo(response.outputStream, bufferSize = 64 * 1024)
                response.outputStream.flush()
            }
        } catch (e: IOException) {
            log.debug("Audio full stream aborted by client for {}: {}", filePath, e.message)
        }
    }

    private fun resolveAudioContentType(
        codec: String?,
        filePath: Path,
    ): String {
        val codecLower = codec?.lowercase().orEmpty()
        val extension = filePath.toString().substringAfterLast('.', missingDelimiterValue = "").lowercase()
        val hint = sequenceOf(codecLower, extension).firstOrNull { it.isNotBlank() }.orEmpty()
        return when {
            hint.contains("flac") -> "audio/flac"
            hint.contains("mp3") || hint == "mpeg" -> "audio/mpeg"
            hint.contains("opus") -> "audio/opus"
            hint.contains("vorbis") || hint == "ogg" -> "audio/ogg"
            hint == "aac" || hint == "m4a" || hint == "alac" || hint.contains("mp4") -> "audio/mp4"
            hint.contains("wav") -> "audio/wav"
            hint.contains("wma") -> "audio/x-ms-wma"
            hint == "ape" -> "audio/x-ape"
            hint.contains("dsf") || hint.contains("dsd") || hint == "dff" -> "audio/x-dsf"
            else -> "application/octet-stream"
        }
    }
}
