package dev.yaytsa.persistence.shared

import dev.yaytsa.application.shared.port.OptimisticLockException
import dev.yaytsa.application.shared.port.TransactionalCommandExecutor
import dev.yaytsa.shared.CommandResult
import dev.yaytsa.shared.Failure
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionTemplate

@Component
class SpringTransactionalCommandExecutor(
    private val txTemplate: TransactionTemplate,
) : TransactionalCommandExecutor {
    override fun <T> execute(block: () -> CommandResult<T>): CommandResult<T> =
        txTemplate.execute { tx ->
            try {
                block()
            } catch (e: OptimisticLockException) {
                tx.setRollbackOnly()
                CommandResult.Failed(Failure.StorageConflict("aggregate", e.message ?: ""))
            } catch (e: DataIntegrityViolationException) {
                tx.setRollbackOnly()
                CommandResult.Failed(Failure.InvariantViolation(e.message ?: "constraint violation"))
            }
        }!!
}
