package dev.yaytsa.application.playback

import dev.yaytsa.application.playback.port.PlayHistoryWritePort
import dev.yaytsa.shared.TrackId
import dev.yaytsa.shared.UserId
import java.time.Duration
import java.time.Instant

/**
 * Evaluates raw playback data and records scrobble (play history) entries.
 *
 * Threshold logic:
 * - **completed**: elapsed > 240 s OR elapsed > 50 % of reported position
 * - **skipped**: not completed AND elapsed < 30 s (only when position is known)
 */
class ScrobbleService(
    private val playHistoryWriter: PlayHistoryWritePort,
) {
    fun recordScrobble(
        userId: UserId,
        trackId: TrackId,
        startedAt: Instant,
        stoppedAt: Instant,
        positionMs: Long,
    ) {
        val elapsedMs = Duration.between(startedAt, stoppedAt).toMillis()
        val completed =
            elapsedMs > COMPLETED_THRESHOLD_MS ||
                (positionMs > 0 && elapsedMs > positionMs / 2)
        val skipped = positionMs > 0 && !completed && elapsedMs < SKIPPED_THRESHOLD_MS

        playHistoryWriter.record(
            userId = userId,
            trackId = trackId,
            startedAt = startedAt,
            durationMs = null,
            playedMs = positionMs,
            completed = completed,
            skipped = skipped,
        )
    }

    companion object {
        const val COMPLETED_THRESHOLD_MS: Long = 240_000
        const val SKIPPED_THRESHOLD_MS: Long = 30_000
    }
}
