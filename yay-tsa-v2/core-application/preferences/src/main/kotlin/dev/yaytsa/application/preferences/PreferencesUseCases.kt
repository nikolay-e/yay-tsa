package dev.yaytsa.application.preferences

import dev.yaytsa.application.preferences.port.UserPreferencesRepository
import dev.yaytsa.application.shared.PayloadFingerprint
import dev.yaytsa.application.shared.ProtocolCapabilitiesRegistry
import dev.yaytsa.application.shared.port.DomainNotification
import dev.yaytsa.application.shared.port.IdempotencyStore
import dev.yaytsa.application.shared.port.OutboxPort
import dev.yaytsa.application.shared.port.TransactionalCommandExecutor
import dev.yaytsa.domain.preferences.PreferencesCommand
import dev.yaytsa.domain.preferences.PreferencesDeps
import dev.yaytsa.domain.preferences.PreferencesHandler
import dev.yaytsa.domain.preferences.SetFavorite
import dev.yaytsa.domain.preferences.UserPreferencesAggregate
import dev.yaytsa.shared.AggregateVersion
import dev.yaytsa.shared.CommandContext
import dev.yaytsa.shared.CommandResult
import dev.yaytsa.shared.Failure
import dev.yaytsa.shared.TrackId
import dev.yaytsa.shared.UserId
import dev.yaytsa.shared.asCommandFailure

class PreferencesUseCases(
    private val prefsRepo: UserPreferencesRepository,
    private val idempotencyStore: IdempotencyStore,
    private val capabilities: ProtocolCapabilitiesRegistry,
    private val txExecutor: TransactionalCommandExecutor,
    private val outbox: OutboxPort,
    private val trackValidator: (Set<TrackId>) -> Set<TrackId>,
) {
    fun execute(
        cmd: PreferencesCommand,
        ctx: CommandContext,
    ): CommandResult<UserPreferencesAggregate> {
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
                val current = prefsRepo.find(cmd.userId) ?: UserPreferencesAggregate.empty(cmd.userId)
                return@execute CommandResult.Success(current, AggregateVersion(existing.resultVersion))
            }

            val snapshot = prefsRepo.find(cmd.userId) ?: UserPreferencesAggregate.empty(cmd.userId)
            val deps = loadDeps(cmd)
            val result = PreferencesHandler.handle(snapshot, cmd, ctx, deps)
            when (result) {
                is CommandResult.Success -> {
                    prefsRepo.save(result.value)
                    idempotencyStore.store(ctx.userId, commandType, ctx.idempotencyKey, payloadHash, result.newVersion.value)
                    outbox.enqueue(DomainNotification.PreferencesChanged(ctx.userId.value))
                }
                is CommandResult.Failed -> {}
            }
            result
        }
    }

    fun find(userId: UserId): UserPreferencesAggregate? = prefsRepo.find(userId)

    private fun loadDeps(cmd: PreferencesCommand): PreferencesDeps {
        val toValidate =
            when (cmd) {
                is SetFavorite -> setOf(cmd.trackId)
                else -> emptySet()
            }
        return PreferencesDeps(knownTrackIds = if (toValidate.isNotEmpty()) trackValidator(toValidate) else emptySet())
    }
}
