package dev.yaytsa.domain.playback

import dev.yaytsa.shared.CommandContext
import dev.yaytsa.shared.CommandResult
import dev.yaytsa.shared.Failure
import dev.yaytsa.shared.TrackId
import dev.yaytsa.shared.asCommandFailure
import dev.yaytsa.shared.asSuccess
import java.time.Duration

object PlaybackHandler {
    private const val MAX_QUEUE_SIZE = 10_000

    fun handle(
        snapshot: PlaybackSessionAggregate,
        cmd: PlaybackCommand,
        ctx: CommandContext,
        deps: PlaybackDeps,
    ): CommandResult<PlaybackSessionAggregate> {
        if (snapshot.version != ctx.expectedVersion) {
            return CommandResult.Failed(Failure.Conflict(ctx.expectedVersion, snapshot.version))
        }
        return when (cmd) {
            is AcquireLease -> acquireLease(snapshot, cmd, ctx)
            is ReleaseLease -> releaseLease(snapshot, cmd, ctx)
            is RefreshLease -> refreshLease(snapshot, cmd, ctx)
            is AddToQueue -> withLease(snapshot, cmd.deviceId, ctx) { addToQueue(it, cmd, deps) }
            is RemoveFromQueue -> withLease(snapshot, cmd.deviceId, ctx) { removeFromQueue(it, cmd, ctx) }
            is ReplaceQueue -> withLease(snapshot, cmd.deviceId, ctx) { replaceQueue(it, cmd, deps) }
            is ClearQueue -> withLease(snapshot, cmd.deviceId, ctx) { clearQueue(it, ctx) }
            is MoveQueueEntry -> withLease(snapshot, cmd.deviceId, ctx) { moveQueueEntry(it, cmd) }
            is Play -> withLease(snapshot, cmd.deviceId, ctx) { play(it, cmd, ctx) }
            is Pause -> withLease(snapshot, cmd.deviceId, ctx) { pause(it, ctx) }
            is Stop -> withLease(snapshot, cmd.deviceId, ctx) { stop(it, ctx) }
            is Seek -> withLease(snapshot, cmd.deviceId, ctx) { seek(it, cmd, ctx, deps) }
            is SkipNext -> withLease(snapshot, cmd.deviceId, ctx) { skipNext(it, ctx) }
            is SkipPrevious -> withLease(snapshot, cmd.deviceId, ctx) { skipPrevious(it, ctx) }
            is StartPlaybackWithTracks -> startPlayback(snapshot, cmd, ctx, deps)
        }
    }

    private fun withLease(
        s: PlaybackSessionAggregate,
        deviceId: DeviceId,
        ctx: CommandContext,
        block: (PlaybackSessionAggregate) -> CommandResult<PlaybackSessionAggregate>,
    ): CommandResult<PlaybackSessionAggregate> {
        val lease = s.lease ?: return Failure.Unauthorized("No active lease").asCommandFailure()
        if (ctx.requestTime >= lease.expiresAt) {
            return Failure.Unauthorized("Lease expired").asCommandFailure()
        }
        if (lease.owner != deviceId) {
            return Failure.Unauthorized("Lease owned by different device").asCommandFailure()
        }
        return block(s)
    }

    private fun acquireLease(
        s: PlaybackSessionAggregate,
        cmd: AcquireLease,
        ctx: CommandContext,
    ): CommandResult<PlaybackSessionAggregate> {
        val existing = s.lease
        if (existing != null && existing.owner != cmd.deviceId && ctx.requestTime < existing.expiresAt) {
            return Failure.Unauthorized("Lease held by another device").asCommandFailure()
        }
        val v = s.version.next()
        return s
            .copy(
                lease = PlaybackLease(cmd.deviceId, ctx.requestTime + cmd.leaseDuration),
                version = v,
            ).asSuccess(v)
    }

    private fun releaseLease(
        s: PlaybackSessionAggregate,
        cmd: ReleaseLease,
        ctx: CommandContext,
    ): CommandResult<PlaybackSessionAggregate> {
        val lease = s.lease ?: return Failure.NotFound("Lease", s.sessionId.value).asCommandFailure()
        if (lease.owner != cmd.deviceId) return Failure.Unauthorized("Not lease owner").asCommandFailure()
        val v = s.version.next()
        return s
            .copy(
                lease = null,
                playbackState = if (s.playbackState == PlaybackState.PLAYING) PlaybackState.PAUSED else s.playbackState,
                lastKnownPosition = s.computePosition(ctx.requestTime),
                lastKnownAt = ctx.requestTime,
                version = v,
            ).asSuccess(v)
    }

    private fun refreshLease(
        s: PlaybackSessionAggregate,
        cmd: RefreshLease,
        ctx: CommandContext,
    ): CommandResult<PlaybackSessionAggregate> {
        val lease = s.lease ?: return Failure.NotFound("Lease", s.sessionId.value).asCommandFailure()
        if (ctx.requestTime >= lease.expiresAt) return Failure.Unauthorized("Lease expired, must re-acquire").asCommandFailure()
        if (lease.owner != cmd.deviceId) return Failure.Unauthorized("Not lease owner").asCommandFailure()
        val v = s.version.next()
        return s
            .copy(
                lease = lease.copy(expiresAt = ctx.requestTime + cmd.leaseDuration),
                version = v,
            ).asSuccess(v)
    }

    private fun addToQueue(
        s: PlaybackSessionAggregate,
        cmd: AddToQueue,
        deps: PlaybackDeps,
    ): CommandResult<PlaybackSessionAggregate> {
        checkTracks(cmd.entries.map { it.trackId }, deps)?.let { return it }
        checkEntryIdsUnique(s.queue, cmd.entries)?.let { return it }
        if (s.queue.size + cmd.entries.size > MAX_QUEUE_SIZE) {
            return Failure.InvariantViolation("Queue size would exceed maximum of $MAX_QUEUE_SIZE").asCommandFailure()
        }
        val v = s.version.next()
        return s.copy(queue = s.queue + cmd.entries, version = v).asSuccess(v)
    }

    private fun removeFromQueue(
        s: PlaybackSessionAggregate,
        cmd: RemoveFromQueue,
        ctx: CommandContext,
    ): CommandResult<PlaybackSessionAggregate> {
        val idx = s.queue.indexOfFirst { it.id == cmd.entryId }
        if (idx == -1) return Failure.NotFound("QueueEntry", cmd.entryId.value).asCommandFailure()
        val newQueue = s.queue.toMutableList().apply { removeAt(idx) }
        val newCurrent = if (s.currentEntryId == cmd.entryId) null else s.currentEntryId
        val snapshotPosition = newCurrent == null && s.playbackState == PlaybackState.PLAYING
        val v = s.version.next()
        return s
            .copy(
                queue = newQueue,
                currentEntryId = newCurrent,
                playbackState = if (snapshotPosition) PlaybackState.STOPPED else s.playbackState,
                lastKnownPosition = if (snapshotPosition) s.computePosition(ctx.requestTime) else s.lastKnownPosition,
                lastKnownAt = if (snapshotPosition) ctx.requestTime else s.lastKnownAt,
                version = v,
            ).asSuccess(v)
    }

    private fun replaceQueue(
        s: PlaybackSessionAggregate,
        cmd: ReplaceQueue,
        deps: PlaybackDeps,
    ): CommandResult<PlaybackSessionAggregate> {
        checkTracks(cmd.entries.map { it.trackId }, deps)?.let { return it }
        val ids = cmd.entries.map { it.id }
        if (ids.size != ids.toSet().size) {
            return Failure.InvariantViolation("Duplicate QueueEntryIds").asCommandFailure()
        }
        val v = s.version.next()
        return s
            .copy(
                queue = cmd.entries,
                currentEntryId = null,
                playbackState = PlaybackState.STOPPED,
                lastKnownPosition = Duration.ZERO,
                version = v,
            ).asSuccess(v)
    }

    private fun clearQueue(
        s: PlaybackSessionAggregate,
        ctx: CommandContext,
    ): CommandResult<PlaybackSessionAggregate> {
        val v = s.version.next()
        return s
            .copy(
                queue = emptyList(),
                currentEntryId = null,
                playbackState = PlaybackState.STOPPED,
                lastKnownPosition = Duration.ZERO,
                lastKnownAt = ctx.requestTime,
                version = v,
            ).asSuccess(v)
    }

    private fun moveQueueEntry(
        s: PlaybackSessionAggregate,
        cmd: MoveQueueEntry,
    ): CommandResult<PlaybackSessionAggregate> {
        val idx = s.queue.indexOfFirst { it.id == cmd.entryId }
        if (idx == -1) return Failure.NotFound("QueueEntry", cmd.entryId.value).asCommandFailure()
        if (cmd.newIndex < 0 || cmd.newIndex >= s.queue.size) return Failure.InvariantViolation("Index out of bounds").asCommandFailure()
        val q = s.queue.toMutableList()
        val e = q.removeAt(idx)
        q.add(cmd.newIndex, e)
        val v = s.version.next()
        return s.copy(queue = q, version = v).asSuccess(v)
    }

    private fun play(
        s: PlaybackSessionAggregate,
        cmd: Play,
        ctx: CommandContext,
    ): CommandResult<PlaybackSessionAggregate> {
        val target = cmd.entryId ?: s.currentEntryId
        if (target != null && s.queue.none { it.id == target }) return Failure.NotFound("QueueEntry", target.value).asCommandFailure()
        if (target == null && s.queue.isEmpty()) return Failure.InvariantViolation("Queue is empty").asCommandFailure()
        val resolved = target ?: s.queue.first().id
        val resetPos = cmd.entryId != null && cmd.entryId != s.currentEntryId
        val v = s.version.next()
        return s
            .copy(
                currentEntryId = resolved,
                playbackState = PlaybackState.PLAYING,
                lastKnownPosition = if (resetPos) Duration.ZERO else s.lastKnownPosition,
                lastKnownAt = ctx.requestTime,
                version = v,
            ).asSuccess(v)
    }

    private fun pause(
        s: PlaybackSessionAggregate,
        ctx: CommandContext,
    ): CommandResult<PlaybackSessionAggregate> {
        if (s.playbackState != PlaybackState.PLAYING) {
            return Failure.InvariantViolation("Can only pause when playing").asCommandFailure()
        }
        val v = s.version.next()
        return s
            .copy(
                playbackState = PlaybackState.PAUSED,
                lastKnownPosition = s.computePosition(ctx.requestTime),
                lastKnownAt = ctx.requestTime,
                version = v,
            ).asSuccess(v)
    }

    private fun stop(
        s: PlaybackSessionAggregate,
        ctx: CommandContext,
    ): CommandResult<PlaybackSessionAggregate> {
        val v = s.version.next()
        return s
            .copy(
                playbackState = PlaybackState.STOPPED,
                lastKnownPosition = Duration.ZERO,
                lastKnownAt = ctx.requestTime,
                version = v,
            ).asSuccess(v)
    }

    private fun seek(
        s: PlaybackSessionAggregate,
        cmd: Seek,
        ctx: CommandContext,
        deps: PlaybackDeps,
    ): CommandResult<PlaybackSessionAggregate> {
        if (s.currentEntryId == null) return Failure.InvariantViolation("No current track").asCommandFailure()
        if (s.playbackState == PlaybackState.STOPPED) {
            return Failure.InvariantViolation("Cannot seek while stopped").asCommandFailure()
        }
        if (cmd.position.isNegative) return Failure.InvariantViolation("Negative seek").asCommandFailure()
        deps.currentTrackDuration?.let { dur ->
            if (cmd.position > dur) return Failure.InvariantViolation("Seek past track end").asCommandFailure()
        }
        val v = s.version.next()
        return s
            .copy(
                lastKnownPosition = cmd.position,
                lastKnownAt = ctx.requestTime,
                version = v,
            ).asSuccess(v)
    }

    private fun skipNext(
        s: PlaybackSessionAggregate,
        ctx: CommandContext,
    ): CommandResult<PlaybackSessionAggregate> {
        val curIdx = s.currentEntryId?.let { id -> s.queue.indexOfFirst { it.id == id } } ?: -1
        val next = curIdx + 1
        if (next >= s.queue.size) return Failure.InvariantViolation("No next track").asCommandFailure()
        val v = s.version.next()
        return s
            .copy(
                currentEntryId = s.queue[next].id,
                playbackState = PlaybackState.PLAYING,
                lastKnownPosition = Duration.ZERO,
                lastKnownAt = ctx.requestTime,
                version = v,
            ).asSuccess(v)
    }

    private fun skipPrevious(
        s: PlaybackSessionAggregate,
        ctx: CommandContext,
    ): CommandResult<PlaybackSessionAggregate> {
        val curIdx =
            s.currentEntryId?.let { id -> s.queue.indexOfFirst { it.id == id } }
                ?: return Failure.InvariantViolation("No current track").asCommandFailure()
        val prev = curIdx - 1
        if (prev < 0) return Failure.InvariantViolation("No previous track").asCommandFailure()
        val v = s.version.next()
        return s
            .copy(
                currentEntryId = s.queue[prev].id,
                playbackState = PlaybackState.PLAYING,
                lastKnownPosition = Duration.ZERO,
                lastKnownAt = ctx.requestTime,
                version = v,
            ).asSuccess(v)
    }

    private fun startPlayback(
        s: PlaybackSessionAggregate,
        cmd: StartPlaybackWithTracks,
        ctx: CommandContext,
        deps: PlaybackDeps,
    ): CommandResult<PlaybackSessionAggregate> {
        val existing = s.lease
        if (existing != null && existing.owner != cmd.deviceId && ctx.requestTime < existing.expiresAt) {
            return Failure.Unauthorized("Lease held by another device").asCommandFailure()
        }
        checkTracks(cmd.entries.map { it.trackId }, deps)?.let { return it }
        val ids = cmd.entries.map { it.id }
        if (ids.size != ids.toSet().size) {
            return Failure.InvariantViolation("Duplicate QueueEntryIds").asCommandFailure()
        }
        if (cmd.entries.isEmpty()) return Failure.InvariantViolation("Empty track list").asCommandFailure()
        val v = s.version.next()
        return s
            .copy(
                lease = PlaybackLease(cmd.deviceId, ctx.requestTime + cmd.leaseDuration),
                queue = cmd.entries,
                currentEntryId = cmd.entries.first().id,
                playbackState = PlaybackState.PLAYING,
                lastKnownPosition = Duration.ZERO,
                lastKnownAt = ctx.requestTime,
                version = v,
            ).asSuccess(v)
    }

    // --- helpers ---

    private fun checkTracks(
        trackIds: List<TrackId>,
        deps: PlaybackDeps,
    ): CommandResult.Failed? {
        val unknown = trackIds.filter { it !in deps.knownTrackIds }
        if (unknown.isNotEmpty()) {
            return CommandResult.Failed(
                Failure.InvariantViolation("Unknown tracks: ${unknown.joinToString { it.value }}"),
            )
        }
        return null
    }

    private fun checkEntryIdsUnique(
        existing: List<QueueEntry>,
        new: List<QueueEntry>,
    ): CommandResult.Failed? {
        val existingIds = existing.map { it.id }.toSet()
        val newIds = new.map { it.id }
        val dupes = newIds.filter { it in existingIds }
        if (dupes.isNotEmpty()) return CommandResult.Failed(Failure.InvariantViolation("Duplicate QueueEntryIds"))
        if (newIds.size != newIds.toSet().size) return CommandResult.Failed(Failure.InvariantViolation("Duplicate QueueEntryIds"))
        return null
    }
}
