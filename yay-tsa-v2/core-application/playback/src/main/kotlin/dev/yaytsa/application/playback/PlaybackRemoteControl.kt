package dev.yaytsa.application.playback

import dev.yaytsa.application.shared.port.Clock
import dev.yaytsa.application.shared.port.RemoteCommandPort
import dev.yaytsa.domain.playback.PlaybackSessionAggregate
import dev.yaytsa.domain.playback.PlaybackState
import dev.yaytsa.domain.playback.SessionId
import dev.yaytsa.shared.DeviceId
import dev.yaytsa.shared.TrackId
import dev.yaytsa.shared.UserId
import dev.yaytsa.shared.generated.RemoteCommandType
import java.time.Duration
import java.time.Instant

sealed interface RemoteControlOutcome {
    data class Confirmed(
        val state: PlaybackState,
        val currentTrackId: TrackId?,
        val deviceName: String?,
    ) : RemoteControlOutcome

    data class SentUnconfirmed(
        val deviceName: String?,
    ) : RemoteControlOutcome

    data class NoReachableDevice(
        val deviceName: String?,
        val lastSeenAt: Instant?,
    ) : RemoteControlOutcome

    data object NoActiveSession : RemoteControlOutcome

    data class InvalidTracks(
        val trackIds: List<String>,
    ) : RemoteControlOutcome
}

class PlaybackRemoteControl(
    private val playbackQueries: PlaybackQueries,
    private val deviceSessionProjection: DeviceSessionProjection,
    private val remoteCommandPort: RemoteCommandPort,
    private val trackValidator: (Set<TrackId>) -> Set<TrackId>,
    private val clock: Clock,
    private val confirmationAttempts: Int = CONFIRMATION_ATTEMPTS,
    private val confirmationInterval: Duration = CONFIRMATION_INTERVAL,
    private val sleeper: (Duration) -> Unit = { Thread.sleep(it.toMillis()) },
) {
    fun sendTransportCommand(
        userId: UserId,
        command: RemoteCommandType,
        sessionId: SessionId? = null,
        params: Map<String, Any?> = emptyMap(),
        awaitConfirmation: Boolean = true,
    ): RemoteControlOutcome {
        val target =
            when (val resolution = resolveTarget(userId, sessionId)) {
                is Resolution.Unreachable -> return resolution.outcome
                is Resolution.Target -> resolution
            }
        val trackBefore = currentTrackId(target.session)
        remoteCommandPort.publish(userId.value, target.ownerDeviceId.value, command.name, params)
        if (!awaitConfirmation || command !in CONFIRMABLE_COMMANDS) {
            return RemoteControlOutcome.SentUnconfirmed(target.deviceName)
        }
        repeat(confirmationAttempts) {
            sleeper(confirmationInterval)
            val observed = playbackQueries.getPlaybackState(userId, target.session.sessionId) ?: return@repeat
            if (isConfirmed(command, observed, trackBefore)) {
                return RemoteControlOutcome.Confirmed(observed.playbackState, currentTrackId(observed), target.deviceName)
            }
        }
        return RemoteControlOutcome.SentUnconfirmed(target.deviceName)
    }

    fun sendQueueCommand(
        userId: UserId,
        command: RemoteCommandType,
        trackIds: List<String> = emptyList(),
        sessionId: SessionId? = null,
    ): RemoteControlOutcome {
        if (trackIds.isNotEmpty()) {
            val known = trackValidator(trackIds.map { TrackId(it) }.toSet())
            val unknown = trackIds.filter { TrackId(it) !in known }
            if (unknown.isNotEmpty()) return RemoteControlOutcome.InvalidTracks(unknown)
        }
        val target =
            when (val resolution = resolveTarget(userId, sessionId)) {
                is Resolution.Unreachable -> return resolution.outcome
                is Resolution.Target -> resolution
            }
        val params = if (command == RemoteCommandType.CLEAR_QUEUE) emptyMap() else mapOf("trackIds" to trackIds)
        remoteCommandPort.publish(userId.value, target.ownerDeviceId.value, command.name, params)
        return RemoteControlOutcome.SentUnconfirmed(target.deviceName)
    }

    private sealed interface Resolution {
        data class Target(
            val session: PlaybackSessionAggregate,
            val ownerDeviceId: DeviceId,
            val deviceName: String?,
        ) : Resolution

        data class Unreachable(
            val outcome: RemoteControlOutcome,
        ) : Resolution
    }

    private fun resolveTarget(
        userId: UserId,
        sessionId: SessionId?,
    ): Resolution {
        val now = clock.now()
        val devices = deviceSessionProjection.getByUser(userId)
        val candidateSessionIds =
            sessionId?.let { listOf(it) }
                ?: devices.sortedByDescending { it.lastSeenAt }.map { it.sessionId }.distinct()
        val sessions = candidateSessionIds.mapNotNull { playbackQueries.getPlaybackState(userId, it) }
        if (sessions.isEmpty()) return Resolution.Unreachable(RemoteControlOutcome.NoActiveSession)
        val leased = sessions.mapNotNull { s -> s.lease?.takeIf { now < it.expiresAt }?.let { s to it.owner } }
        if (leased.isEmpty()) {
            return Resolution.Unreachable(RemoteControlOutcome.NoReachableDevice(null, null))
        }
        val reachable =
            leased.firstNotNullOfOrNull { (session, owner) ->
                devices
                    .firstOrNull { it.deviceId == owner }
                    ?.takeIf { Duration.between(it.lastSeenAt, now) <= ONLINE_WINDOW }
                    ?.let { Resolution.Target(session, owner, it.deviceName) }
            }
        if (reachable != null) return reachable
        val staleOwner = devices.firstOrNull { device -> leased.any { it.second == device.deviceId } }
        return Resolution.Unreachable(RemoteControlOutcome.NoReachableDevice(staleOwner?.deviceName, staleOwner?.lastSeenAt))
    }

    private fun isConfirmed(
        command: RemoteCommandType,
        observed: PlaybackSessionAggregate,
        trackBefore: TrackId?,
    ): Boolean =
        when (command) {
            RemoteCommandType.PAUSE -> observed.playbackState == PlaybackState.PAUSED
            RemoteCommandType.PLAY -> observed.playbackState == PlaybackState.PLAYING
            RemoteCommandType.NEXT, RemoteCommandType.PREV ->
                currentTrackId(observed).let { it != null && it != trackBefore }
            else -> false
        }

    private fun currentTrackId(session: PlaybackSessionAggregate): TrackId? =
        session.currentEntryId?.let { entryId -> session.queue.firstOrNull { it.id == entryId }?.trackId }

    companion object {
        private val ONLINE_WINDOW: Duration = Duration.ofSeconds(45)
        private const val CONFIRMATION_ATTEMPTS = 6
        private val CONFIRMATION_INTERVAL: Duration = Duration.ofMillis(500)
        private val CONFIRMABLE_COMMANDS =
            setOf(RemoteCommandType.PAUSE, RemoteCommandType.PLAY, RemoteCommandType.NEXT, RemoteCommandType.PREV)
    }
}
