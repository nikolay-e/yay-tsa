package dev.yaytsa.app.metrics

import dev.yaytsa.application.shared.port.TransactionalCommandExecutor
import dev.yaytsa.persistence.shared.SpringTransactionalCommandExecutor
import dev.yaytsa.shared.Command
import dev.yaytsa.shared.CommandResult
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import org.springframework.context.annotation.Primary
import org.springframework.stereotype.Component
import java.util.concurrent.TimeUnit

@Primary
@Component
class MeteredTransactionalCommandExecutor(
    private val delegate: SpringTransactionalCommandExecutor,
    private val meterRegistry: MeterRegistry,
) : TransactionalCommandExecutor {
    override fun <T> execute(
        command: Command,
        block: () -> CommandResult<T>,
    ): CommandResult<T> {
        val startNanos = System.nanoTime()
        try {
            val result = delegate.execute(command, block)
            record(command, outcomeOf(result), startNanos)
            return result
        } catch (e: Exception) {
            record(command, "UnhandledException", startNanos)
            throw e
        }
    }

    private fun outcomeOf(result: CommandResult<*>): String =
        when (result) {
            is CommandResult.Success -> "success"
            is CommandResult.Failed -> result.failure::class.simpleName ?: "failure"
        }

    private fun record(
        command: Command,
        outcome: String,
        startNanos: Long,
    ) {
        Timer
            .builder("yaytsa.command.execution")
            .tag("context", contextOf(command))
            .tag("command", command::class.simpleName ?: "unknown")
            .tag("outcome", outcome)
            .register(meterRegistry)
            .record(System.nanoTime() - startNanos, TimeUnit.NANOSECONDS)
    }

    private fun contextOf(command: Command): String = command::class.java.packageName.substringAfterLast('.')
}
