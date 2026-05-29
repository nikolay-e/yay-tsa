package dev.yaytsa.adapterjellyfin

import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

internal object MediaPathSafety {
    fun resolveRoot(configuredRoot: String?): Path? =
        configuredRoot
            ?.takeIf { it.isNotBlank() }
            ?.let { runCatching { Path.of(it).toRealPath() }.getOrNull() }

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
