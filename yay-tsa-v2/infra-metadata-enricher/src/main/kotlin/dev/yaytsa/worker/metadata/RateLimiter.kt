package dev.yaytsa.worker.metadata

import dev.yaytsa.application.shared.port.Clock

class RateLimiter(
    private val clock: Clock,
    private val minIntervalMs: Long = 1_000,
) {
    private var lastRequestAtMs = Long.MIN_VALUE

    @Synchronized
    fun acquire() {
        val nowMs = clock.now().toEpochMilli()
        val earliestNext = lastRequestAtMs + minIntervalMs
        if (nowMs < earliestNext) {
            val waitMs = earliestNext - nowMs
            try {
                Thread.sleep(waitMs)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw e
            }
        }
        lastRequestAtMs = clock.now().toEpochMilli()
    }
}
