package dev.yaytsa.shared

sealed interface ByteRangeResult {
    data object FullContent : ByteRangeResult

    data object Unsatisfiable : ByteRangeResult

    data class Satisfiable(
        val start: Long,
        val requestedEnd: Long,
    ) : ByteRangeResult
}

object ByteRangeParser {
    fun parse(
        rangeHeader: String?,
        fileSize: Long,
    ): ByteRangeResult {
        if (rangeHeader == null || !rangeHeader.startsWith("bytes=") || rangeHeader.contains(",")) {
            return ByteRangeResult.FullContent
        }
        val parts = rangeHeader.removePrefix("bytes=").split("-")
        val suffixSpec = parts.size == 2 && parts[0].isEmpty()
        val start: Long
        val requestedEnd: Long
        if (suffixSpec) {
            start = (fileSize - (parts[1].toLongOrNull() ?: 0L)).coerceAtLeast(0L)
            requestedEnd = fileSize - 1
        } else {
            start = parts[0].toLongOrNull() ?: 0L
            requestedEnd =
                if (parts.size > 1 && parts[1].isNotEmpty()) {
                    parts[1].toLongOrNull() ?: (fileSize - 1)
                } else {
                    fileSize - 1
                }
        }
        // RFC 7233: 416 only when first-byte-pos is unsatisfiable; an over-long end is clamped by the caller.
        if (start < 0 || start >= fileSize || requestedEnd < start) {
            return ByteRangeResult.Unsatisfiable
        }
        return ByteRangeResult.Satisfiable(start, requestedEnd)
    }
}
