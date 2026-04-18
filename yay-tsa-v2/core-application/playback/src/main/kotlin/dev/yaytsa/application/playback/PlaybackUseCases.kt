package dev.yaytsa.application.playback

import dev.yaytsa.application.playback.port.PlaybackSessionRepository
import dev.yaytsa.application.shared.PayloadFingerprint
import dev.yaytsa.application.shared.ProtocolCapabilitiesRegistry
import dev.yaytsa.application.shared.port.DomainNotification
import dev.yaytsa.application.shared.port.IdempotencyStore
import dev.yaytsa.application.shared.port.OutboxPort
import dev.yaytsa.application.shared.port.TransactionalCommandExecutor
import dev.yaytsa.domain.playback.AddToQueue
import dev.yaytsa.domain.playback.PlaybackCommand
import dev.yaytsa.domain.playback.PlaybackDeps
import dev.yaytsa.domain.playback.PlaybackHandler
import dev.yaytsa.domain.playback.PlaybackSessionAggregate
import dev.yaytsa.domain.playback.ReplaceQueue
import dev.yaytsa.domain.playback.Seek
import dev.yaytsa.domain.playback.SessionId
import dev.yaytsa.domain.playback.StartPlaybackWithTracks
import dev.yaytsa.shared.AggregateVersion
import dev.yaytsa.shared.CommandContext
import dev.yaytsa.shared.CommandResult
import dev.yaytsa.shared.Failure
import dev.yaytsa.shared.TrackId
import dev.yaytsa.shared.UserId
import dev.yaytsa.shared.asCommandFailure
import java.time.Duration

class PlaybackUseCases(
    private val sessionRepo: PlaybackSessionRepository,
    private val idempotencyStore: IdempotencyStore,
    private val capabilities: ProtocolCapabilitiesRegistry,
    private val txExecutor: TransactionalCommandExecutor,
    private val outbox: OutboxPort,
    private val trackValidator: (Set<TrackId>) -> Set<TrackId>,
    private val trackDurationLoader: (TrackId) -> Duration?,
) {
    fun execute(
        cmd: PlaybackCommand,
        ctx: CommandContext,
    ): CommandResult<PlaybackSessionAggregate> {
        if (!capabilities.isCommandSupported(ctx.protocolId, cmd::class)) {
            return Failure.UnsupportedByProtocol(ctx.protocolId, cmd::class.simpleName ?: "unknown").asCommandFailure()
        }

        val commandType = cmd::class.qualifiedName ?: cmd::class.simpleName ?: "unknown"
        val payloadHash = PayloadFingerprint.compute(cmd)

        return txExecutor.execute {
            val existing = idempotencyStore.find(ctx.userId, commandType, ctx.idempotencyKey)
            if (existing != null) {
                if (existing.payloadHash != payloadHash) {
                    return@execute Failure.InvariantViolation("Idempotency key reused with different payload").asCommandFailure()
                }
                val current =
                    sessionRepo.find(ctx.userId, cmd.sessionId)
                        ?: return@execute Failure.NotFound("PlaybackSession", cmd.sessionId.value).asCommandFailure()
                return@execute CommandResult.Success(current, AggregateVersion(existing.resultVersion))
            }

            // Playback sessions are implicitly created on first command. An empty aggregate
            // with version=INITIAL will fail OCC/lease checks for most commands, so only
            // AcquireLease and StartPlaybackWithTracks can meaningfully succeed on a new session.
            val snapshot =
                sessionRepo.find(ctx.userId, cmd.sessionId)
                    ?: PlaybackSessionAggregate.empty(ctx.userId, cmd.sessionId, ctx.requestTime)
            val deps = loadDeps(snapshot, cmd)
            val result = PlaybackHandler.handle(snapshot, cmd, ctx, deps)
            when (result) {
                is CommandResult.Success -> {
                    sessionRepo.save(result.value)
                    idempotencyStore.store(ctx.userId, commandType, ctx.idempotencyKey, payloadHash, result.newVersion.value)
                    outbox.enqueue(DomainNotification.PlaybackStateChanged(ctx.userId.value, cmd.sessionId.value))
                }
                is CommandResult.Failed -> {}
            }
            result
        }
    }

    fun getPlaybackState(
        userId: UserId,
        sessionId: SessionId,
    ): PlaybackSessionAggregate? = sessionRepo.find(userId, sessionId)

    private fun loadDeps(
        snapshot: PlaybackSessionAggregate,
        cmd: PlaybackCommand,
    ): PlaybackDeps {
        val trackIdsToValidate = mutableSetOf<TrackId>()
        when (cmd) {
            is AddToQueue -> trackIdsToValidate.addAll(cmd.entries.map { it.trackId })
            is ReplaceQueue -> trackIdsToValidate.addAll(cmd.entries.map { it.trackId })
            is StartPlaybackWithTracks -> trackIdsToValidate.addAll(cmd.entries.map { it.trackId })
            else -> {}
        }
        val knownTrackIds = if (trackIdsToValidate.isNotEmpty()) trackValidator(trackIdsToValidate) else emptySet()
        val currentTrackDuration =
            if (cmd is Seek) {
                snapshot.currentEntryId?.let { entryId ->
                    snapshot.queue
                        .find { it.id == entryId }
                        ?.trackId
                        ?.let { trackDurationLoader(it) }
                }
            } else {
                null
            }
        return PlaybackDeps(knownTrackIds = knownTrackIds, currentTrackDuration = currentTrackDuration)
    }
}
