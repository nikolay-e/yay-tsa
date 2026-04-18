package dev.yaytsa.domain.playback

import dev.yaytsa.shared.AggregateVersion
import dev.yaytsa.shared.TrackId
import dev.yaytsa.shared.UserId
import java.time.Duration
import java.time.Instant

/**
 * Playback session with variant B position model:
 * position stored as last_known_position_ms + last_known_at.
 * Current position computed lazily: if PLAYING, position = lastKnownPosition + (now - lastKnownAt).
 */
data class PlaybackSessionAggregate(
    val userId: UserId,
    val sessionId: SessionId,
    val queue: List<QueueEntry>,
    val currentEntryId: QueueEntryId?,
    val playbackState: PlaybackState,
    val lastKnownPosition: Duration,
    val lastKnownAt: Instant,
    val lease: PlaybackLease?,
    val version: AggregateVersion,
) {
    companion object {
        fun empty(
            userId: UserId,
            sessionId: SessionId,
            now: Instant,
        ) = PlaybackSessionAggregate(
            userId = userId,
            sessionId = sessionId,
            queue = emptyList(),
            currentEntryId = null,
            playbackState = PlaybackState.STOPPED,
            lastKnownPosition = Duration.ZERO,
            lastKnownAt = now,
            lease = null,
            version = AggregateVersion.INITIAL,
        )
    }

    fun computePosition(now: Instant): Duration =
        when (playbackState) {
            PlaybackState.PLAYING -> {
                val elapsed = Duration.between(lastKnownAt, now)
                lastKnownPosition + if (elapsed.isNegative) Duration.ZERO else elapsed
            }
            else -> lastKnownPosition
        }
}

data class QueueEntry(
    val id: QueueEntryId,
    val trackId: TrackId,
)

enum class PlaybackState { PLAYING, PAUSED, STOPPED }

data class PlaybackLease(
    val owner: DeviceId,
    val expiresAt: Instant,
)
