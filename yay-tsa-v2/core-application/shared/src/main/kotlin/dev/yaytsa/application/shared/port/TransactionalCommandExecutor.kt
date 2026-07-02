package dev.yaytsa.application.shared.port

import dev.yaytsa.shared.Command
import dev.yaytsa.shared.CommandResult

/**
 * Wraps command execution in a transaction. Catches storage-level optimistic lock
 * exceptions and converts them to Failure.StorageConflict.
 *
 * Use case calls: txExecutor.execute(cmd) { load → handle → save → idempotency }
 * The command is passed alongside the block so infrastructure decorators (metrics,
 * tracing) can observe which command ran without the use case knowing about them.
 * Notification publishing happens AFTER the transaction commits.
 */
interface TransactionalCommandExecutor {
    fun <T> execute(
        command: Command,
        block: () -> CommandResult<T>,
    ): CommandResult<T>
}
