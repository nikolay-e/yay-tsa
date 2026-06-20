package dev.yaytsa.worker.scanner

import org.jaudiotagger.audio.AudioFileIO
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.logging.Level
import java.util.logging.Logger
import kotlin.io.path.exists

data class ExtractedCover(
    val path: Path,
    val sizeBytes: Long,
)

@Component
class EmbeddedCoverExtractor(
    @Value("\${yaytsa.image.cover-cache-dir:#{null}}") cacheDir: String?,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    private val cacheRoot: Path? =
        cacheDir
            ?.takeIf { it.isNotBlank() }
            ?.let { runCatching { Files.createDirectories(Path.of(it)) }.getOrNull() }

    init {
        Logger.getLogger("org.jaudiotagger").level = Level.SEVERE
    }

    fun extractToCache(audioFile: Path): ExtractedCover? {
        val root = cacheRoot ?: return null
        val mtime = runCatching { Files.getLastModifiedTime(audioFile).toMillis() }.getOrNull() ?: return null
        val size = runCatching { Files.size(audioFile) }.getOrNull() ?: return null
        val key = sha256Hex("${audioFile.toAbsolutePath()}|$mtime|$size|embedded")
        val ext = key.take(2)
        val bucket = runCatching { Files.createDirectories(root.resolve(ext)) }.getOrNull() ?: root
        val cached = bucket.resolve("$key.img")
        if (cached.exists()) {
            return runCatching { Files.size(cached) }.getOrNull()?.let { ExtractedCover(cached, it) }
        }

        return try {
            val artwork = AudioFileIO.read(audioFile.toFile()).tag?.firstArtwork ?: return null
            val bytes = artwork.binaryData ?: return null
            if (bytes.isEmpty()) return null
            val tmp = Files.createTempFile(bucket, "art", ".tmp")
            Files.write(tmp, bytes)
            Files.move(tmp, cached, StandardCopyOption.REPLACE_EXISTING)
            ExtractedCover(cached, bytes.size.toLong())
        } catch (e: Exception) {
            log.debug("Embedded cover extraction failed for {}: {}", audioFile, e.toString())
            null
        }
    }

    private fun sha256Hex(value: String): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
