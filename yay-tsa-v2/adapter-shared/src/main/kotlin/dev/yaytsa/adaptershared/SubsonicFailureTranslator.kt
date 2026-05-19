package dev.yaytsa.adaptershared

import dev.yaytsa.shared.Failure

data class SubsonicErrorPayload(
    val code: Int,
    val message: String,
)

class SubsonicFailureTranslator : FailureTranslator<SubsonicErrorPayload> {
    override fun translate(failure: Failure): SubsonicErrorPayload =
        when (failure) {
            is Failure.NotFound -> SubsonicErrorPayload(70, "${failure.entityType} not found")
            is Failure.Unauthorized -> SubsonicErrorPayload(50, "Permission denied")
            is Failure.Conflict -> SubsonicErrorPayload(0, "Conflict: ${failure.expected.value} vs ${failure.actual.value}")
            is Failure.StorageConflict -> SubsonicErrorPayload(0, "Concurrent modification on ${failure.aggregateType}")
            is Failure.UnsupportedByProtocol -> SubsonicErrorPayload(30, "Operation not supported")
            is Failure.InvariantViolation -> SubsonicErrorPayload(10, failure.rule)
        }
}
