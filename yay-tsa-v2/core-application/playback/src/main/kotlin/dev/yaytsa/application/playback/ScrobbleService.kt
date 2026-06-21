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
 * - **completed**: in-track position reached >= 50 % of the track duration,
 *   OR wall-clock elapsed > 240 s (coarse backstop when duration is unknown)
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
        runTimeMs: Long,
    ) {
        val elapsedMs = Duration.between(startedAt, stoppedAt).toMillis()
        val completed =
            elapsedMs > COMPLETED_THRESHOLD_MS ||
                (runTimeMs > 0 && positionMs > runTimeMs / 2)
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
