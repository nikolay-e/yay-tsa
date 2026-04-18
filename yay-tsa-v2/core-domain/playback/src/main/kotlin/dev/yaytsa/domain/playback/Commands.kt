package dev.yaytsa.domain.playback

import dev.yaytsa.shared.Command
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

// Atomic composite
data class StartPlaybackWithTracks(
    override val sessionId: SessionId,
    val deviceId: DeviceId,
    val leaseDuration: Duration,
    val entries: List<QueueEntry>,
) : PlaybackCommand
