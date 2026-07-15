package dev.yaytsa.adapterjellyfin

import dev.yaytsa.adaptershared.MediaPathSafety
import dev.yaytsa.application.library.LibraryQueries
import dev.yaytsa.shared.ByteRangeParser
import dev.yaytsa.shared.ByteRangeResult
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
import org.springframework.web.server.ResponseStatusException
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
    private val embeddedArtwork: EmbeddedArtworkService,
    @Value("\${yaytsa.library.music-path:#{null}}") musicPath: String?,
    @Value("\${yaytsa.metadata.artist-image-dir:#{null}}") artistImageDir: String?,
    @Value("\${yaytsa.image.cover-cache-dir:#{null}}") coverCacheDir: String?,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val safeRoot = MediaPathSafety.resolveRoot(musicPath)
    private val artistImageRoot = MediaPathSafety.resolveRoot(artistImageDir)
    private val coverCacheRoot = MediaPathSafety.resolveRoot(coverCacheDir)
    private val imageRoots = listOfNotNull(safeRoot, artistImageRoot, coverCacheRoot)

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
        val filePath =
            libraryQueries
                .getPrimaryImage(EntityId(itemId))
                ?.let { resolveServableImage(it.path) }
                ?: embeddedArtworkFile(itemId)
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

    // Primary images live either under the music root (album cover.jpg written next to the audio)
    // or under the dedicated artist-image cache dir (artists are not filesystem folders). Accept a
    // file confined to any configured safe root.
    private fun resolveServableImage(rawPath: String): Path? {
        val candidate = Path.of(rawPath)
        return imageRoots.firstNotNullOfOrNull { MediaPathSafety.resolveServableFile(candidate, it) }
    }

    // Folder has no cover file (audiobook rips ship art only inside the tags): pull the
    // embedded artwork from the item's own file, or the first chapter when the id is an album.
    private fun embeddedArtworkFile(itemId: String): Path? {
        val entityId = EntityId(itemId)
        val trackId =
            if (libraryQueries.getTrack(entityId) != null) {
                entityId
            } else {
                libraryQueries.browseTracksByAlbum(entityId).firstOrNull()?.id ?: return null
            }
        val sourcePath = libraryQueries.resolveTrackFilePath(trackId) ?: return null
        val audioFile = MediaPathSafety.resolveServableFile(Path.of(sourcePath), safeRoot) ?: return null
        return embeddedArtwork.extract(audioFile)
    }

    @GetMapping("/Audio/{itemId}/universal")
    fun universalAudio(
        @PathVariable itemId: String,
        @RequestParam(name = "api_key", required = false) apiKey: String?,
        @RequestParam(required = false) deviceId: String?,
        @RequestParam(required = false) container: String?,
        @RequestParam(required = false) audioCodec: String?,
        @RequestHeader(HttpHeaders.RANGE, required = false) range: String?,
        response: HttpServletResponse,
    ) {
        streamAudio(itemId, apiKey, deviceId, container, audioCodec, range, response)
    }

    @GetMapping("/Audio/{itemId}/stream")
    fun streamAudio(
        @PathVariable itemId: String,
        @RequestParam(name = "api_key", required = false) apiKey: String?,
        @RequestParam(required = false) deviceId: String?,
        @RequestParam(required = false) container: String?,
        @RequestParam(required = false) audioCodec: String?,
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

        rejectUndecodableAudio(container, audioCodec, track.codec, filePath)

        val contentType = resolveAudioContentType(track.codec, filePath)
        val fileSize = Files.size(filePath)

        when (val parsedRange = ByteRangeParser.parse(range, fileSize)) {
            is ByteRangeResult.Unsatisfiable -> {
                response.status = 416
                response.setHeader(HttpHeaders.CONTENT_RANGE, "bytes */$fileSize")
            }
            is ByteRangeResult.Satisfiable -> {
                val start = parsedRange.start
                // RFC 7233: an over-long end is clamped to EOF.
                val end = minOf(parsedRange.requestedEnd, fileSize - 1)
                val length = end - start + 1
                response.status = 206
                response.contentType = contentType
                response.setHeader(HttpHeaders.CONTENT_LENGTH, length.toString())
                response.setHeader(HttpHeaders.CONTENT_RANGE, "bytes $start-$end/$fileSize")
                response.setHeader(HttpHeaders.ACCEPT_RANGES, "bytes")
                writeRangeQuietly(filePath, start, length, response)
            }
            is ByteRangeResult.FullContent -> {
                response.status = 200
                response.contentType = contentType
                response.setHeader(HttpHeaders.CONTENT_LENGTH, fileSize.toString())
                response.setHeader(HttpHeaders.ACCEPT_RANGES, "bytes")
                writeFullFileQuietly(filePath, response)
            }
        }
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

    private fun rejectUndecodableAudio(
        declaredContainers: String?,
        declaredCodecs: String?,
        codec: String?,
        filePath: Path,
    ) {
        if (declaredContainers.isNullOrBlank() && declaredCodecs.isNullOrBlank()) return
        val fileContainer = normalizeFormatToken(filePath.toString().substringAfterLast('.', missingDelimiterValue = "").lowercase())
        val fileCodec = normalizeFormatToken(codec?.lowercase().orEmpty())
        if (clientCanPlayStoredAudio(declaredContainers, declaredCodecs, fileContainer, fileCodec)) return
        throw ResponseStatusException(
            HttpStatus.UNSUPPORTED_MEDIA_TYPE,
            "Track is stored as container '$fileContainer' codec '$fileCodec', which the client did not declare as playable; transcoding is not supported",
        )
    }

    private fun clientCanPlayStoredAudio(
        declaredContainers: String?,
        declaredCodecs: String?,
        fileContainer: String,
        fileCodec: String,
    ): Boolean {
        val containerEntries = splitFormatList(declaredContainers)
        val containerMatch =
            containerEntries.any { entry ->
                val parts = entry.split('|', limit = 2).map { normalizeFormatToken(it.trim()) }
                if (parts.size == 2) {
                    parts[0] == fileContainer && parts[1] == fileCodec
                } else {
                    parts[0] == fileContainer || parts[0] == fileCodec
                }
            }
        if (containerMatch) return true
        val codecEntries = splitFormatList(declaredCodecs).map { normalizeFormatToken(it) }
        return containerEntries.isEmpty() && fileCodec.isNotBlank() && fileCodec in codecEntries
    }

    private fun splitFormatList(raw: String?): List<String> =
        raw
            .orEmpty()
            .lowercase()
            .split(',')
            .map { it.trim() }
            .filter { it.isNotBlank() }

    private fun normalizeFormatToken(token: String): String =
        when (token) {
            "mpeg" -> "mp3"
            "mp4" -> "m4a"
            "oga", "vorbis" -> "ogg"
            else -> token
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
