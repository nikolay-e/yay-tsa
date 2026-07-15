package dev.yaytsa.domain.playback

import dev.yaytsa.shared.AggregateVersion
import dev.yaytsa.shared.DeviceId
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

    fun needsExternalReflection(
        reportedTrackId: TrackId,
        reportedPositionMs: Long,
        reportedState: PlaybackState,
        reportingDeviceId: DeviceId,
        now: Instant,
        leaseRenewThreshold: Duration,
        positionToleranceMs: Long,
    ): Boolean {
        val currentTrackId = currentEntryId?.let { entryId -> queue.firstOrNull { it.id == entryId }?.trackId }
        if (currentTrackId != reportedTrackId) return true
        if (playbackState != reportedState) return true
        val lease = lease
        if (lease == null || lease.owner != reportingDeviceId || Duration.between(now, lease.expiresAt) < leaseRenewThreshold) return true
        val driftMs = computePosition(now).toMillis() - reportedPositionMs
        return driftMs > positionToleranceMs || driftMs < -positionToleranceMs
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
