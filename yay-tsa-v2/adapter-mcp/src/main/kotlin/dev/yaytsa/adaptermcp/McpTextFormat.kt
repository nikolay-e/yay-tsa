package dev.yaytsa.adaptermcp

import java.util.Locale

internal fun msToClock(ms: Long): String {
    val totalSeconds = ms / 1000
    return "%d:%02d".format(Locale.ROOT, totalSeconds / 60, totalSeconds % 60)
}

internal fun rate(value: Double): String = "%.2f".format(Locale.ROOT, value)

internal fun intArg(
    args: Map<String, Any?>,
    key: String,
    default: Int,
): Int = (args[key] as? Number)?.toInt() ?: default

internal fun stringListArg(
    args: Map<String, Any?>,
    key: String,
): List<String> = (args[key] as? List<*>)?.mapNotNull { it as? String }.orEmpty()
