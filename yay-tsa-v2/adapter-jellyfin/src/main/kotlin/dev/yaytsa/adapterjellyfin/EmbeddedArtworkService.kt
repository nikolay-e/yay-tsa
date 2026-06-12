package dev.yaytsa.adapterjellyfin

import org.jaudiotagger.audio.AudioFileIO
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.logging.Level
import java.util.logging.Logger
import kotlin.io.path.exists

/**
 * Serves cover art embedded in audio tags (ID3 APIC / FLAC picture blocks) for items whose
 * folder carries no image file — audiobook rips routinely ship art only inside the tags, so the
 * scanner has no image row to index and the Images endpoint would otherwise 404 forever.
 *
 * Extracted bytes are cached on disk keyed by sha256(absPath | mtime | size), so a retagged or
 * replaced file naturally invalidates and repeated requests are pure cache reads.
 */
@Service
class EmbeddedArtworkService(
    @Value("\${yaytsa.image.cache-dir:#{null}}") cacheDir: String?,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val cacheRoot: Path =
        (cacheDir?.let { Path.of(it) } ?: Path.of(System.getProperty("java.io.tmpdir"), "yaytsa-thumbnails"))
            .also { runCatching { Files.createDirectories(it) } }

    init {
        Logger.getLogger("org.jaudiotagger").level = Level.SEVERE
    }

    fun extract(audioFile: Path): Path? {
        val mtime = runCatching { Files.getLastModifiedTime(audioFile).toMillis() }.getOrNull() ?: return null
        val size = runCatching { Files.size(audioFile) }.getOrNull() ?: return null
        val key = sha256("${audioFile.toAbsolutePath()}|$mtime|$size|embedded")
        val cached = cacheRoot.resolve("$key.img")
        if (cached.exists()) return cached

        return try {
            val artwork = AudioFileIO.read(audioFile.toFile()).tag?.firstArtwork ?: return null
            val bytes = artwork.binaryData ?: return null
            if (bytes.isEmpty()) return null
            val tmp = Files.createTempFile(cacheRoot, "art", ".tmp")
            Files.write(tmp, bytes)
            Files.move(tmp, cached, StandardCopyOption.REPLACE_EXISTING)
            cached
        } catch (e: Exception) {
            log.debug("Embedded artwork extraction failed for {}: {}", audioFile, e.toString())
            null
        }
    }

    private fun sha256(s: String): String = MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
}
