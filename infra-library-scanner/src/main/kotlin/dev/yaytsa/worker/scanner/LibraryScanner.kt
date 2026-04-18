package dev.yaytsa.worker.scanner

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension

@Component
class LibraryScanner(
    private val libraryWriter: LibraryWriter,
    @Value("\${yaytsa.library.music-path:#{null}}") private val musicPath: String?,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    private val audioExtensions = setOf("mp3", "flac", "ogg", "opus", "m4a", "aac", "wav", "wma", "alac")

    @Scheduled(fixedDelay = 3_600_000, initialDelay = 5_000)
    fun scan() {
        val root =
            musicPath ?: run {
                log.info("yaytsa.library.music-path not configured, skipping scan")
                return
            }
        val rootPath = Path.of(root)
        if (!Files.isDirectory(rootPath)) {
            log.warn("Music path does not exist or is not a directory: {}", root)
            return
        }

        log.info("Starting library scan: {}", root)
        var count = 0

        Files
            .walk(rootPath)
            .filter { Files.isRegularFile(it) }
            .filter { it.extension.lowercase() in audioExtensions }
            .forEach { file ->
                try {
                    libraryWriter.upsertTrack(rootPath, file)
                    count++
                } catch (e: Exception) {
                    log.error("Failed to process file: {}", file, e)
                }
            }

        log.info("Library scan complete: {} tracks processed", count)
    }
}
