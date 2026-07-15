package dev.yaytsa.adaptershared

import dev.yaytsa.shared.Failure
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes

fun problemDetail(
    status: HttpStatus,
    title: String,
    detail: String,
): ResponseEntity<Any> =
    ResponseEntity
        .status(status)
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .body(
            buildMap<String, Any> {
                put("type", "about:blank")
                put("title", title)
                put("status", status.value())
                put("detail", detail)
                currentRequestUri()?.let { put("instance", it) }
            },
        )

private fun currentRequestUri(): String? = (RequestContextHolder.getRequestAttributes() as? ServletRequestAttributes)?.request?.requestURI

class HttpFailureTranslator : FailureTranslator<ResponseEntity<Any>> {
    override fun translate(failure: Failure): ResponseEntity<Any> {
        val status = statusFor(failure)
        return problemDetail(status, status.reasonPhrase, describe(failure))
    }

    fun statusFor(failure: Failure): HttpStatus =
        when (failure) {
            is Failure.NotFound -> HttpStatus.NOT_FOUND
            is Failure.Unauthorized -> HttpStatus.UNAUTHORIZED
            is Failure.Conflict -> HttpStatus.CONFLICT
            is Failure.StorageConflict -> HttpStatus.CONFLICT
            is Failure.UnsupportedByProtocol -> HttpStatus.NOT_IMPLEMENTED
            is Failure.InvariantViolation -> HttpStatus.BAD_REQUEST
        }

    fun describe(failure: Failure): String =
        when (failure) {
            is Failure.NotFound -> "${failure.entityType} '${failure.id}' not found"
            is Failure.Unauthorized -> failure.reason
            is Failure.Conflict -> "Version conflict: expected ${failure.expected.value}, actual ${failure.actual.value}"
            is Failure.StorageConflict -> "Concurrent modification of ${failure.aggregateType} '${failure.id}'"
            is Failure.InvariantViolation -> failure.rule
            is Failure.UnsupportedByProtocol -> "Command '${failure.command}' not supported by protocol '${failure.protocol.value}'"
        }

    fun empty(failure: Failure): ResponseEntity<Void> = ResponseEntity.status(statusFor(failure)).build()
}
