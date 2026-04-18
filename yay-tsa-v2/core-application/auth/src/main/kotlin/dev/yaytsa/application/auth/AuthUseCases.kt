package dev.yaytsa.application.auth

import dev.yaytsa.application.auth.port.UserRepository
import dev.yaytsa.application.shared.PayloadFingerprint
import dev.yaytsa.application.shared.ProtocolCapabilitiesRegistry
import dev.yaytsa.application.shared.port.IdempotencyStore
import dev.yaytsa.application.shared.port.OutboxPort
import dev.yaytsa.application.shared.port.TransactionalCommandExecutor
import dev.yaytsa.domain.auth.AuthCommand
import dev.yaytsa.domain.auth.AuthHandler
import dev.yaytsa.domain.auth.CreateUser
import dev.yaytsa.domain.auth.UserAggregate
import dev.yaytsa.shared.AggregateVersion
import dev.yaytsa.shared.CommandContext
import dev.yaytsa.shared.CommandResult
import dev.yaytsa.shared.Failure
import dev.yaytsa.shared.UserId
import dev.yaytsa.shared.asCommandFailure

class AuthUseCases(
    private val userRepo: UserRepository,
    private val idempotencyStore: IdempotencyStore,
    private val capabilities: ProtocolCapabilitiesRegistry,
    private val txExecutor: TransactionalCommandExecutor,
    private val outbox: OutboxPort,
) {
    fun execute(
        cmd: AuthCommand,
        ctx: CommandContext,
    ): CommandResult<UserAggregate> {
        if (!capabilities.isCommandSupported(ctx.protocolId, cmd::class)) {
            return Failure
                .UnsupportedByProtocol(ctx.protocolId, cmd::class.simpleName ?: "unknown")
                .asCommandFailure()
        }

        val commandType = cmd::class.qualifiedName ?: cmd::class.simpleName ?: "unknown"
        val payloadHash = PayloadFingerprint.compute(cmd)

        return txExecutor.execute {
            // Idempotency check inside transaction to prevent TOCTOU
            val existing = idempotencyStore.find(ctx.userId, commandType, ctx.idempotencyKey)
            if (existing != null) {
                if (existing.payloadHash != payloadHash) {
                    return@execute Failure
                        .InvariantViolation("Idempotency key reused with different payload")
                        .asCommandFailure()
                }
                // Command already applied — return current state
                val current =
                    userRepo.find(cmd.userId)
                        ?: return@execute Failure.NotFound("User", cmd.userId.value).asCommandFailure()
                return@execute CommandResult.Success(current, AggregateVersion(existing.resultVersion))
            }

            val snapshot = userRepo.find(cmd.userId)
            if (snapshot == null && cmd !is CreateUser) {
                return@execute Failure.NotFound("User", cmd.userId.value).asCommandFailure()
            }

            val result = AuthHandler.handle(snapshot, cmd, ctx)

            when (result) {
                is CommandResult.Success -> {
                    userRepo.save(result.value)
                    idempotencyStore.store(ctx.userId, commandType, ctx.idempotencyKey, payloadHash, result.newVersion.value)
                    outbox.enqueue(
                        dev.yaytsa.application.shared.port.DomainNotification
                            .AuthChanged(cmd.userId.value),
                    )
                }
                is CommandResult.Failed -> {}
            }

            result
        }
    }

    fun findUser(userId: UserId): UserAggregate? = userRepo.find(userId)

    fun findByUsername(username: String): UserAggregate? = userRepo.findByUsername(username)

    fun findByApiToken(token: String): UserAggregate? = userRepo.findByApiToken(token)
}
