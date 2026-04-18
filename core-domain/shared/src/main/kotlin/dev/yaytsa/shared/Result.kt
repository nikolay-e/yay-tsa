package dev.yaytsa.shared

sealed interface CommandResult<out T> {
    data class Success<T>(
        val value: T,
        val newVersion: AggregateVersion,
    ) : CommandResult<T>

    data class Failed(
        val failure: Failure,
    ) : CommandResult<Nothing>
}

sealed interface QueryResult<out T> {
    data class Success<T>(
        val value: T,
    ) : QueryResult<T>

    data class Failed(
        val failure: Failure,
    ) : QueryResult<Nothing>
}

fun <T> T.asSuccess(newVersion: AggregateVersion): CommandResult<T> = CommandResult.Success(this, newVersion)

fun <T> Failure.asCommandFailure(): CommandResult<T> = CommandResult.Failed(this)

fun <T> Failure.asQueryFailure(): QueryResult<T> = QueryResult.Failed(this)

fun <T> T.asQuerySuccess(): QueryResult<T> = QueryResult.Success(this)
