package dev.yaytsa.adaptershared

import dev.yaytsa.shared.Failure

class MpdFailureTranslator : FailureTranslator<String> {
    override fun translate(failure: Failure): String = translate(failure, "command")

    fun translate(
        failure: Failure,
        commandName: String,
    ): String {
        val (code, msg) =
            when (failure) {
                is Failure.NotFound -> 50 to "${failure.entityType} not found"
                is Failure.Unauthorized -> 4 to "Permission denied"
                is Failure.Conflict -> 5 to "Conflict: ${failure.expected.value} vs ${failure.actual.value}"
                is Failure.StorageConflict -> 5 to "Concurrent modification"
                is Failure.UnsupportedByProtocol -> 5 to "Unsupported"
                is Failure.InvariantViolation -> 2 to failure.rule
            }
        return "ACK [$code@0] {$commandName} $msg\n"
    }
}
