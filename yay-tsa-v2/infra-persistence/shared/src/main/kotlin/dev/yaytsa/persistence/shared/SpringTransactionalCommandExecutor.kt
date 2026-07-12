package dev.yaytsa.persistence.shared

import dev.yaytsa.application.shared.port.OptimisticLockException
import dev.yaytsa.application.shared.port.TransactionalCommandExecutor
import dev.yaytsa.shared.Command
import dev.yaytsa.shared.CommandResult
import dev.yaytsa.shared.Failure
import org.slf4j.LoggerFactory
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionTemplate

@Component
class SpringTransactionalCommandExecutor(
    private val txTemplate: TransactionTemplate,
) : TransactionalCommandExecutor {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun <T> execute(
        command: Command,
        block: () -> CommandResult<T>,
    ): CommandResult<T> =
        txTemplate.execute { tx ->
            try {
                block()
            } catch (e: OptimisticLockException) {
                tx.setRollbackOnly()
                CommandResult.Failed(Failure.StorageConflict("aggregate", e.message ?: ""))
            } catch (e: DataIntegrityViolationException) {
                tx.setRollbackOnly()
                // The raw persistence message embeds the failing SQL (schema, table, column list,
                // bound values) — surfacing it in the RFC7807 `detail` leaks the DB schema to the
                // client. Log the real cause server-side; return a generic, non-leaking failure.
                log.warn("Data integrity violation executing {}: {}", command::class.simpleName, e.message)
                CommandResult.Failed(Failure.InvariantViolation("The request violates a data constraint"))
            }
        }!!
}
