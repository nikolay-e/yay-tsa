package dev.yaytsa.worker.scanner

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.nio.file.FileVisitOption
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.name

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
        val presentSourcePaths = HashSet<String>()
        var walkCompleted = false

        try {
            Files
                .walk(rootPath, FileVisitOption.FOLLOW_LINKS)
                .filter { Files.isRegularFile(it) }
                .filter { path -> path.none { segment -> segment.name.startsWith(".") } }
                .filter { it.extension.lowercase() in audioExtensions }
                .forEach { file ->
                    presentSourcePaths.add(rootPath.relativize(file).toString())
                    try {
                        libraryWriter.upsertTrack(rootPath, file)
                        count++
                    } catch (e: Exception) {
                        log.error("Failed to process file: {}", file, e)
                    }
                }
            walkCompleted = true
        } catch (e: Exception) {
            log.error("Library walk failed; skipping reconcile to avoid deleting live rows", e)
        }

        log.info("Library scan complete: {} tracks processed", count)

        if (!walkCompleted) return

        try {
            val removedTracks = libraryWriter.deleteVanishedTracks(rootPath, presentSourcePaths)
            if (removedTracks > 0) {
                log.info("Removed {} vanished tracks for root {}", removedTracks, root)
            }
        } catch (e: Exception) {
            log.error("Failed to reconcile vanished tracks", e)
        }

        try {
            val orphanedAlbums = libraryWriter.deleteOrphanAlbums()
            if (orphanedAlbums > 0) {
                log.info("Removed {} orphan albums", orphanedAlbums)
            }
        } catch (e: Exception) {
            log.error("Failed to clean up orphan albums", e)
        }

        try {
            val orphanedArtists = libraryWriter.deleteOrphanArtists()
            if (orphanedArtists > 0) {
                log.info("Removed {} orphan compound artist entities", orphanedArtists)
            }
        } catch (e: Exception) {
            log.error("Failed to clean up orphan artists", e)
        }
    }
}
