package dev.yaytsa.worker.scanner

import dev.yaytsa.application.library.port.LibraryUploadIngestPort
import dev.yaytsa.application.library.port.UploadIngestResult
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import org.jaudiotagger.tag.Tag
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

@Component
class UploadedTrackIngest(
    private val libraryWriter: LibraryWriter,
    @Value("\${yaytsa.library.music-path:#{null}}") private val musicPath: String?,
) : LibraryUploadIngestPort {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun ingest(
        sourceFile: Path,
        originalFilename: String,
    ): UploadIngestResult {
        val root = musicPath?.takeIf { it.isNotBlank() }?.let { Path.of(it) }
        if (root == null || !Files.isDirectory(root)) return UploadIngestResult.LibraryRootUnavailable
        val realRoot = root.toRealPath()

        val extension = originalFilename.substringAfterLast('.', "").lowercase()
        val tag = runCatching { AudioFileIO.read(sourceFile.toFile()).tag }.getOrNull()
        val artistSegment = (tag?.usableField(FieldKey.ALBUM_ARTIST) ?: tag?.usableField(FieldKey.ARTIST))?.let(::sanitizeSegment)
        val albumSegment = tag?.usableField(FieldKey.ALBUM)?.let(::sanitizeSegment)
        val targetDir =
            if (artistSegment != null && albumSegment != null) {
                root.resolve(artistSegment).resolve(albumSegment)
            } else {
                root.resolve(UPLOADS_FOLDER)
            }
        // toRealPath() throws NoSuchFileException on a path that doesn't exist yet, so containment
        // can only be checked lexically before creation happens. That rejects a "../"-style escape
        // without ever touching disk. It cannot see a symlink planted at an intermediate segment
        // (normalize() is purely syntactic) - that case is only detectable after Files.createDirectories
        // has already materialized something on disk, so we re-verify with toRealPath() below and fail
        // closed, deleting whatever got created, if the escape only shows up post-creation.
        if (!targetDir.normalize().startsWith(root.normalize())) {
            log.warn("Rejecting upload whose target directory escapes the library root: {}", targetDir)
            return UploadIngestResult.NotIngestable
        }
        Files.createDirectories(targetDir)
        if (!targetDir.toRealPath().startsWith(realRoot)) {
            log.warn("Rejecting upload whose target directory escapes the library root: {}", targetDir)
            Files.deleteIfExists(targetDir)
            return UploadIngestResult.NotIngestable
        }

        val baseName = sanitizeSegment(originalFilename.substringBeforeLast('.')) ?: "track"
        val placement = placeWithoutOverwrite(targetDir, baseName, extension, sourceFile)
        val trackId = libraryWriter.upsertTrack(root, placement.target)
        if (trackId == null) {
            if (!placement.duplicate) Files.deleteIfExists(placement.target)
            return UploadIngestResult.NotIngestable
        }
        log.info("Ingested uploaded track {} as {} (duplicate={})", placement.target, trackId, placement.duplicate)
        return UploadIngestResult.Ingested(trackId.toString(), root.relativize(placement.target).toString(), placement.duplicate)
    }

    private data class Placement(
        val target: Path,
        val duplicate: Boolean,
    )

    private fun placeWithoutOverwrite(
        targetDir: Path,
        baseName: String,
        extension: String,
        sourceFile: Path,
    ): Placement {
        for (attempt in 0..MAX_SUFFIX_ATTEMPTS) {
            val name = if (attempt == 0) "$baseName.$extension" else "$baseName ($attempt).$extension"
            val candidate = targetDir.resolve(name)
            if (!Files.exists(candidate)) {
                Files.move(sourceFile, candidate)
                return Placement(candidate, duplicate = false)
            }
            if (Files.mismatch(candidate, sourceFile) == -1L) {
                return Placement(candidate, duplicate = true)
            }
        }
        val fallback = targetDir.resolve("$baseName-${UUID.randomUUID().toString().take(8)}.$extension")
        Files.move(sourceFile, fallback)
        return Placement(fallback, duplicate = false)
    }

    private fun sanitizeSegment(raw: String): String? =
        raw
            .replace(Regex("[/\\\\:*?\"<>|]"), " ")
            .replace(Regex("\\p{Cntrl}"), "")
            .trim()
            .trimStart('.')
            .take(MAX_SEGMENT_LENGTH)
            .trim()
            .takeIf { it.isNotBlank() }

    private fun Tag.usableField(field: FieldKey): String? =
        runCatching { getFirst(field) }
            .getOrNull()
            ?.trim()
            ?.takeIf { it.isNotBlank() }

    companion object {
        private const val UPLOADS_FOLDER = "Uploads"
        private const val MAX_SUFFIX_ATTEMPTS = 20
        private const val MAX_SEGMENT_LENGTH = 120
    }
}
