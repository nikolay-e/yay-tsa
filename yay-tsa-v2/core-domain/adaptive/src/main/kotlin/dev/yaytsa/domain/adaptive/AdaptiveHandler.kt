package dev.yaytsa.domain.adaptive

import dev.yaytsa.shared.CommandContext
import dev.yaytsa.shared.CommandResult
import dev.yaytsa.shared.Failure
import dev.yaytsa.shared.asCommandFailure
import dev.yaytsa.shared.asSuccess

object AdaptiveHandler {
    fun handle(
        snapshot: AdaptiveSessionAggregate?,
        cmd: AdaptiveCommand,
        ctx: CommandContext,
        deps: AdaptiveDeps,
    ): CommandResult<AdaptiveSessionAggregate> =
        when (cmd) {
            is StartListeningSession -> startSession(snapshot, cmd, ctx)
            is EndListeningSession -> withExisting(snapshot, cmd, ctx) { s -> endSession(s, cmd, ctx) }
            is UpdateSessionContext -> withExisting(snapshot, cmd, ctx) { s -> updateContext(s, cmd, ctx) }
            is RewriteQueueTail -> withExisting(snapshot, cmd, ctx) { s -> rewriteQueueTail(s, cmd, ctx, deps) }
            is RecordPlaybackSignal -> withExisting(snapshot, cmd, ctx) { s -> recordSignal(s, cmd, ctx) }
        }

    private fun startSession(
        snapshot: AdaptiveSessionAggregate?,
        cmd: StartListeningSession,
        ctx: CommandContext,
    ): CommandResult<AdaptiveSessionAggregate> {
        if (snapshot != null) {
            return Failure.InvariantViolation("Session already exists").asCommandFailure()
        }
        val agg =
            AdaptiveSessionAggregate.start(
                id = cmd.sessionId,
                userId = ctx.userId,
                attentionMode = cmd.attentionMode,
                seedTrackId = cmd.seedTrackId,
                seedGenres = cmd.seedGenres,
                now = ctx.requestTime,
            )
        return agg.asSuccess(agg.version)
    }

    private fun withExisting(
        snapshot: AdaptiveSessionAggregate?,
        cmd: AdaptiveCommand,
        ctx: CommandContext,
        block: (AdaptiveSessionAggregate) -> CommandResult<AdaptiveSessionAggregate>,
    ): CommandResult<AdaptiveSessionAggregate> {
        if (snapshot == null) {
            return Failure.NotFound("AdaptiveSession", cmd.sessionId.value).asCommandFailure()
        }
        if (snapshot.version != ctx.expectedVersion) {
            return CommandResult.Failed(Failure.Conflict(ctx.expectedVersion, snapshot.version))
        }
        return block(snapshot)
    }

    private fun endSession(
        s: AdaptiveSessionAggregate,
        cmd: EndListeningSession,
        ctx: CommandContext,
    ): CommandResult<AdaptiveSessionAggregate> {
        if (s.state == SessionState.ENDED) {
            return Failure.InvariantViolation("Session already ended").asCommandFailure()
        }
        val v = s.version.next()
        return s
            .copy(
                state = SessionState.ENDED,
                endedAt = ctx.requestTime,
                sessionSummary = cmd.summary,
                lastActivityAt = ctx.requestTime,
                version = v,
            ).asSuccess(v)
    }

    private fun updateContext(
        s: AdaptiveSessionAggregate,
        cmd: UpdateSessionContext,
        ctx: CommandContext,
    ): CommandResult<AdaptiveSessionAggregate> {
        if (s.state == SessionState.ENDED) {
            return Failure.InvariantViolation("Session already ended").asCommandFailure()
        }
        val v = s.version.next()
        return s
            .copy(
                energy = cmd.energy,
                intensity = cmd.intensity,
                moodTags = cmd.moodTags,
                attentionMode = cmd.attentionMode,
                lastActivityAt = ctx.requestTime,
                version = v,
            ).asSuccess(v)
    }

    private fun recordSignal(
        s: AdaptiveSessionAggregate,
        cmd: RecordPlaybackSignal,
        ctx: CommandContext,
    ): CommandResult<AdaptiveSessionAggregate> {
        if (s.state == SessionState.ENDED) {
            return Failure.InvariantViolation("Session already ended").asCommandFailure()
        }
        val v = s.version.next()
        return s
            .copy(
                lastActivityAt = ctx.requestTime,
                version = v,
            ).asSuccess(v)
    }

    private fun rewriteQueueTail(
        s: AdaptiveSessionAggregate,
        cmd: RewriteQueueTail,
        ctx: CommandContext,
        deps: AdaptiveDeps,
    ): CommandResult<AdaptiveSessionAggregate> {
        if (s.state == SessionState.ENDED) {
            return Failure.InvariantViolation("Session already ended").asCommandFailure()
        }
        if (cmd.baseQueueVersion != s.queueVersion) {
            return Failure.InvariantViolation("Stale queue version: expected ${s.queueVersion}, got ${cmd.baseQueueVersion}").asCommandFailure()
        }
        val unknownTracks = cmd.newTail.map { it.trackId }.filter { it !in deps.knownTrackIds }
        if (unknownTracks.isNotEmpty()) {
            return Failure.InvariantViolation("Unknown tracks: ${unknownTracks.joinToString { it.value }}").asCommandFailure()
        }
        val newIds = cmd.newTail.map { it.id }
        if (newIds.size != newIds.toSet().size) {
            return Failure.InvariantViolation("Duplicate entry IDs").asCommandFailure()
        }
        if (cmd.keepFromPosition < 0) {
            return Failure.InvariantViolation("keepFromPosition must be non-negative").asCommandFailure()
        }
        val maxPosition = if (s.queue.isEmpty()) 0 else s.queue.maxOf { it.position } + 1
        if (cmd.keepFromPosition > maxPosition) {
            return Failure.InvariantViolation("keepFromPosition (${cmd.keepFromPosition}) exceeds queue bounds ($maxPosition)").asCommandFailure()
        }
        val kept = s.queue.filter { it.position < cmd.keepFromPosition }
        val newQueueVersion = s.queueVersion + 1
        val tail =
            cmd.newTail.mapIndexed { idx, entry ->
                AdaptiveQueueEntryData(
                    id = entry.id,
                    trackId = entry.trackId,
                    position = cmd.keepFromPosition + idx,
                    addedReason = entry.addedReason,
                    intentLabel = entry.intentLabel,
                    queueVersion = newQueueVersion,
                    addedAt = ctx.requestTime,
                )
            }
        val v = s.version.next()
        return s
            .copy(
                queue = kept + tail,
                queueVersion = newQueueVersion,
                lastActivityAt = ctx.requestTime,
                version = v,
            ).asSuccess(v)
    }
}
