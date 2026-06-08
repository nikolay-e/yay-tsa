package dev.yaytsa.application.playback

import java.time.Instant

enum class ResumeStatus {
    NOT_STARTED,
    IN_PROGRESS,
    FINISHED,
    RELISTENING,
    ;

    fun wireValue(): String = name.lowercase()

    companion object {
        fun fromWire(value: String): ResumeStatus = entries.firstOrNull { it.wireValue() == value.lowercase() } ?: IN_PROGRESS
    }
}

object ResumeSource {
    const val START = "start"
    const val PROGRESS = "progress"
    const val PAUSE = "pause"
    const val SEEK = "seek"
    const val STOPPED = "stopped"
}

const val RESUME_COMPLETION_THRESHOLD: Double = 0.95
const val RESUME_RESTART_THRESHOLD: Double = 0.05

data class ResumePosition(
    val userId: String,
    val itemId: String,
    val positionMs: Long,
    val runTimeMs: Long,
    val status: ResumeStatus,
    val sourceEvent: String,
    val updatedAt: Instant,
) {
    val progressFraction: Double
        get() = if (runTimeMs > 0) (positionMs.toDouble() / runTimeMs).coerceIn(0.0, 1.0) else 0.0
}

private fun completionFraction(
    positionMs: Long,
    runTimeMs: Long,
): Double = if (runTimeMs > 0) positionMs.toDouble() / runTimeMs else 0.0

/**
 * Pure resume-progress merge. Decides the durable resume row from the prior row and an incoming event.
 *
 * Invariants:
 * - Strictly older events are ignored (stale offline/out-of-order delivery cannot rewind newer progress).
 * - Authoritative events (stopped / pause / seek) set the exact position — a deliberate backward seek is honored.
 * - Heartbeat `progress` only advances forward (furthest-position-wins), so a stale low heartbeat never rewinds.
 * - `start` never moves the position; it only seeds a new row or flips a finished book into relistening.
 * - A finished book resumed near the top becomes `relistening` with the position reset.
 */
fun mergeResume(
    existing: ResumePosition?,
    incoming: ResumePosition,
): ResumePosition {
    if (existing == null) {
        return incoming.copy(status = deriveFreshStatus(incoming))
    }
    if (incoming.updatedAt.isBefore(existing.updatedAt)) return existing

    val runTime = maxOf(existing.runTimeMs, incoming.runTimeMs)

    if (existing.status == ResumeStatus.FINISHED) {
        val fraction = completionFraction(incoming.positionMs, runTime)
        val finishedStatus =
            when {
                fraction >= RESUME_COMPLETION_THRESHOLD -> ResumeStatus.FINISHED
                else -> ResumeStatus.RELISTENING
            }
        return existing.copy(
            positionMs = incoming.positionMs,
            runTimeMs = runTime,
            status = finishedStatus,
            sourceEvent = incoming.sourceEvent,
            updatedAt = incoming.updatedAt,
        )
    }

    val newPosition =
        when (incoming.sourceEvent) {
            ResumeSource.STOPPED, ResumeSource.PAUSE, ResumeSource.SEEK -> incoming.positionMs
            ResumeSource.PROGRESS -> maxOf(existing.positionMs, incoming.positionMs)
            else -> existing.positionMs
        }
    val newStatus =
        when {
            completionFraction(newPosition, runTime) >= RESUME_COMPLETION_THRESHOLD -> ResumeStatus.FINISHED
            existing.status == ResumeStatus.RELISTENING -> ResumeStatus.RELISTENING
            newPosition > 0 -> ResumeStatus.IN_PROGRESS
            else -> existing.status
        }
    return existing.copy(
        positionMs = newPosition,
        runTimeMs = runTime,
        status = newStatus,
        sourceEvent = incoming.sourceEvent,
        updatedAt = incoming.updatedAt,
    )
}

private fun deriveFreshStatus(incoming: ResumePosition): ResumeStatus =
    when {
        completionFraction(incoming.positionMs, incoming.runTimeMs) >= RESUME_COMPLETION_THRESHOLD -> ResumeStatus.FINISHED
        incoming.positionMs > 0 -> ResumeStatus.IN_PROGRESS
        else -> ResumeStatus.NOT_STARTED
    }
