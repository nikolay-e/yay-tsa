package dev.yaytsa.application.adaptive

import dev.yaytsa.application.adaptive.port.AdaptiveSessionRepository
import dev.yaytsa.application.adaptive.port.PlaybackSignalWritePort
import dev.yaytsa.application.shared.PayloadFingerprint
import dev.yaytsa.application.shared.ProtocolCapabilitiesRegistry
import dev.yaytsa.application.shared.port.DomainNotification
import dev.yaytsa.application.shared.port.IdempotencyStore
import dev.yaytsa.application.shared.port.OutboxPort
import dev.yaytsa.application.shared.port.TransactionalCommandExecutor
import dev.yaytsa.domain.adaptive.AdaptiveCommand
import dev.yaytsa.domain.adaptive.AdaptiveDeps
import dev.yaytsa.domain.adaptive.AdaptiveHandler
import dev.yaytsa.domain.adaptive.AdaptiveSessionAggregate
import dev.yaytsa.domain.adaptive.RecordPlaybackSignal
import dev.yaytsa.domain.adaptive.RewriteQueueTail
import dev.yaytsa.domain.adaptive.StartListeningSession
import dev.yaytsa.shared.AggregateVersion
import dev.yaytsa.shared.CommandContext
import dev.yaytsa.shared.CommandResult
import dev.yaytsa.shared.Failure
import dev.yaytsa.shared.TrackId
import dev.yaytsa.shared.asCommandFailure

class AdaptiveUseCases(
    private val sessionRepo: AdaptiveSessionRepository,
    private val idempotencyStore: IdempotencyStore,
    private val capabilities: ProtocolCapabilitiesRegistry,
    private val txExecutor: TransactionalCommandExecutor,
    private val outbox: OutboxPort,
    private val trackValidator: (Set<TrackId>) -> Set<TrackId>,
    private val signalWriter: PlaybackSignalWritePort,
) {
    fun execute(
        cmd: AdaptiveCommand,
        ctx: CommandContext,
    ): CommandResult<AdaptiveSessionAggregate> {
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
                val current = sessionRepo.find(cmd.sessionId)
                return@execute if (current != null) {
                    CommandResult.Success(current, AggregateVersion(existing.resultVersion))
                } else {
                    Failure.NotFound("AdaptiveSession", cmd.sessionId.value).asCommandFailure()
                }
            }

            val snapshot = if (cmd is StartListeningSession) null else sessionRepo.find(cmd.sessionId)
            if (snapshot == null && cmd !is StartListeningSession) {
                return@execute Failure.NotFound("AdaptiveSession", cmd.sessionId.value).asCommandFailure()
            }
            val deps = loadDeps(cmd)
            val result = AdaptiveHandler.handle(snapshot, cmd, ctx, deps)
            when (result) {
                is CommandResult.Success -> {
                    sessionRepo.save(result.value)
                    if (cmd is RecordPlaybackSignal) {
                        signalWriter.save(
                            id = cmd.signalId,
                            sessionId = cmd.sessionId,
                            trackId = cmd.trackId,
                            queueEntryId = cmd.queueEntryId,
                            signalType = cmd.signalType,
                            context = cmd.signalContext,
                            createdAt = ctx.requestTime,
                        )
                    }
                    idempotencyStore.store(ctx.userId, commandType, ctx.idempotencyKey, payloadHash, result.newVersion.value)
                    outbox.enqueue(DomainNotification.AdaptiveQueueChanged(cmd.sessionId.value))
                }
                is CommandResult.Failed -> {}
            }
            result
        }
    }

    private fun loadDeps(cmd: AdaptiveCommand): AdaptiveDeps {
        val trackIdsToValidate = mutableSetOf<TrackId>()
        when (cmd) {
            is RewriteQueueTail -> trackIdsToValidate.addAll(cmd.newTail.map { it.trackId })
            else -> {}
        }
        val knownTrackIds = if (trackIdsToValidate.isNotEmpty()) trackValidator(trackIdsToValidate) else emptySet()
        return AdaptiveDeps(knownTrackIds = knownTrackIds)
    }
}
