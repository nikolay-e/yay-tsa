package dev.yaytsa.domain.playback

import dev.yaytsa.shared.Command
import dev.yaytsa.shared.DeviceId
import dev.yaytsa.shared.TrackId
import java.time.Duration

sealed interface PlaybackCommand : Command {
    val sessionId: SessionId
}

// Lease commands
data class AcquireLease(
    override val sessionId: SessionId,
    val deviceId: DeviceId,
    val leaseDuration: Duration,
) : PlaybackCommand

data class ReleaseLease(
    override val sessionId: SessionId,
    val deviceId: DeviceId,
) : PlaybackCommand

data class RefreshLease(
    override val sessionId: SessionId,
    val deviceId: DeviceId,
    val leaseDuration: Duration,
) : PlaybackCommand

data class TransferLease(
    override val sessionId: SessionId,
    val fromDeviceId: DeviceId,
    val toDeviceId: DeviceId,
    val leaseDuration: Duration,
) : PlaybackCommand

// Queue commands — all require deviceId for lease ownership check
data class AddToQueue(
    override val sessionId: SessionId,
    val deviceId: DeviceId,
    val entries: List<QueueEntry>,
) : PlaybackCommand

data class RemoveFromQueue(
    override val sessionId: SessionId,
    val deviceId: DeviceId,
    val entryId: QueueEntryId,
) : PlaybackCommand

data class ReplaceQueue(
    override val sessionId: SessionId,
    val deviceId: DeviceId,
    val entries: List<QueueEntry>,
) : PlaybackCommand

data class ClearQueue(
    override val sessionId: SessionId,
    val deviceId: DeviceId,
) : PlaybackCommand

data class MoveQueueEntry(
    override val sessionId: SessionId,
    val deviceId: DeviceId,
    val entryId: QueueEntryId,
    val newIndex: Int,
) : PlaybackCommand

// Playback control — all require deviceId for lease ownership check
data class Play(
    override val sessionId: SessionId,
    val deviceId: DeviceId,
    val entryId: QueueEntryId? = null,
) : PlaybackCommand

data class Pause(
    override val sessionId: SessionId,
    val deviceId: DeviceId,
) : PlaybackCommand

data class Stop(
    override val sessionId: SessionId,
    val deviceId: DeviceId,
) : PlaybackCommand

data class Seek(
    override val sessionId: SessionId,
    val deviceId: DeviceId,
    val position: Duration,
) : PlaybackCommand

data class SkipNext(
    override val sessionId: SessionId,
    val deviceId: DeviceId,
) : PlaybackCommand

data class SkipPrevious(
    override val sessionId: SessionId,
    val deviceId: DeviceId,
) : PlaybackCommand

// External playback reflection — an adapter mirrors device-local playback (e.g. the
// PWA's Jellyfin progress reports) into the authoritative session so every protocol
// surface (MCP, /Sessions, devices SSE, MPD) sees the same now-playing state. The
// reporting device becomes the lease owner; a session actively leased by a different
// device rejects the reflection instead of fighting over authority.
data class ReflectExternalPlayback(
    override val sessionId: SessionId,
    val deviceId: DeviceId,
    val trackId: TrackId,
    val entryId: QueueEntryId,
    val position: Duration,
    val state: PlaybackState,
    val leaseDuration: Duration,
) : PlaybackCommand

// Atomic composite
data class StartPlaybackWithTracks(
    override val sessionId: SessionId,
    val deviceId: DeviceId,
    val leaseDuration: Duration,
    val entries: List<QueueEntry>,
) : PlaybackCommand
