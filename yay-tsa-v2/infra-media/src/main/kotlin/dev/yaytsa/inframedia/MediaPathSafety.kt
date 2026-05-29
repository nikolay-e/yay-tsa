package dev.yaytsa.inframedia

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
        if (safeRoot != null && !real.startsWith(safeRoot)) {
            return null
        }
        return real
    }
}
