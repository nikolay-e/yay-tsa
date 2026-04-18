package dev.yaytsa.application.shared.port

import dev.yaytsa.shared.CommandResult

/**
 * Wraps command execution in a transaction. Catches storage-level optimistic lock
 * exceptions and converts them to Failure.StorageConflict.
 *
 * Use case calls: txExecutor.execute { load → handle → save → idempotency }
 * Notification publishing happens AFTER the transaction commits.
 */
interface TransactionalCommandExecutor {
    fun <T> execute(block: () -> CommandResult<T>): CommandResult<T>
}
