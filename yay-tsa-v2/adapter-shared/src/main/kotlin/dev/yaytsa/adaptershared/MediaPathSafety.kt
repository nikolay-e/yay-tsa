package dev.yaytsa.adaptershared

import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

object MediaPathSafety {
    private val STEM_AUDIO_EXTENSIONS = listOf("mp3", "opus", "m4a", "flac", "ogg", "wav")

    fun resolveRoot(configuredRoot: String?): Path? =
        configuredRoot
            ?.takeIf { it.isNotBlank() }
            ?.let { runCatching { Path.of(it).toRealPath() }.getOrNull() }

    fun resolveStemFile(
        candidate: Path,
        safeRoot: Path?,
    ): Path? {
        resolveServableFile(candidate, safeRoot)?.let { return it }
        val parent = candidate.parent ?: return null
        val name = candidate.fileName?.toString() ?: return null
        val dot = name.lastIndexOf('.')
        val stem = if (dot >= 0) name.substring(0, dot) else name
        for (ext in STEM_AUDIO_EXTENSIONS) {
            val sibling = parent.resolve("$stem.$ext")
            if (sibling != candidate) {
                resolveServableFile(sibling, safeRoot)?.let { return it }
            }
        }
        return null
    }

    fun resolveServableFile(
        candidate: Path,
        safeRoot: Path?,
    ): Path? {
        if (safeRoot == null) {
            return null
        }
        if (!Files.exists(candidate, LinkOption.NOFOLLOW_LINKS) ||
            !Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS)
        ) {
            return null
        }
        val real =
            try {
                candidate.toRealPath()
            } catch (_: IOException) {
                return null
            }
        if (!real.startsWith(safeRoot)) {
            return null
        }
        return real
    }
}
