package dev.yaytsa.shared

sealed interface Failure {
    data class NotFound(
        val entityType: String,
        val id: String,
    ) : Failure

    data class Unauthorized(
        val reason: String,
    ) : Failure

    /** Domain-level version mismatch (handler detected stale expectedVersion). */
    data class Conflict(
        val expected: AggregateVersion,
        val actual: AggregateVersion,
    ) : Failure

    /** Storage-level version mismatch (DB rejected write due to concurrent modification). */
    data class StorageConflict(
        val aggregateType: String,
        val id: String,
    ) : Failure

    data class InvariantViolation(
        val rule: String,
    ) : Failure

    data class UnsupportedByProtocol(
        val protocol: ProtocolId,
        val command: String,
    ) : Failure
}
