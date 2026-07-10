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
 * - **completed**: in-track position reached >= 50 % of the known track duration;
 *   the wall-clock 240 s backstop applies ONLY when the duration is unknown, so a
 *   long-paused partial listen of a known track is never stamped completed.
 * - **skipped**: not completed AND position >= 3 s. Sub-3-s stops are queue-surfing
 *   noise and get neither flag. Wall-clock elapsed is deliberately excluded: the
 *   playbackStarts cache misses on restart/eviction, collapsing elapsed to ~0 and
 *   stamping minutes of real listening as skips (49 % skip inflation in prod).
 */
class ScrobbleService(
    private val playHistoryWriter: PlayHistoryWritePort,
) {
    @Suppress("LongParameterList")
    fun recordScrobble(
        userId: UserId,
        trackId: TrackId,
        startedAt: Instant,
        stoppedAt: Instant,
        positionMs: Long,
        runTimeMs: Long,
        source: String? = null,
        deviceId: String? = null,
    ) {
        // Fuzzed/garbage reports can carry astronomic positions; clamp so a single bad
        // wire value can never poison duration sums or overflow downstream aggregates.
        val positionCapMs = if (runTimeMs > 0) minOf(runTimeMs, MAX_POSITION_MS) else MAX_POSITION_MS
        val position = positionMs.coerceIn(0, positionCapMs)
        val completed =
            if (runTimeMs > 0) {
                position >= runTimeMs / 2
            } else {
                Duration.between(startedAt, stoppedAt).toMillis() > COMPLETED_THRESHOLD_MS
            }
        val skipped = !completed && position >= SKIP_NOISE_FLOOR_MS

        playHistoryWriter.record(
            userId = userId,
            trackId = trackId,
            startedAt = startedAt,
            durationMs = runTimeMs.takeIf { it > 0 },
            playedMs = position,
            completed = completed,
            skipped = skipped,
            source = source,
            deviceId = deviceId,
        )
    }

    companion object {
        const val COMPLETED_THRESHOLD_MS: Long = 240_000
        const val SKIP_NOISE_FLOOR_MS: Long = 3_000
        const val MAX_POSITION_MS: Long = 86_400_000
    }
}
