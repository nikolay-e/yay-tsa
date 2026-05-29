package dev.yaytsa.adaptershared

import dev.yaytsa.shared.Failure
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity

fun problemDetail(
    status: HttpStatus,
    title: String,
    detail: String,
): ResponseEntity<Any> =
    ResponseEntity
        .status(status)
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .body(
            mapOf(
                "type" to "about:blank",
                "title" to title,
                "status" to status.value(),
                "detail" to detail,
            ),
        )

class HttpFailureTranslator : FailureTranslator<ResponseEntity<Any>> {
    override fun translate(failure: Failure): ResponseEntity<Any> {
        val status = statusFor(failure)
        return problemDetail(status, status.reasonPhrase, failure.toString())
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

    fun empty(failure: Failure): ResponseEntity<Void> = ResponseEntity.status(statusFor(failure)).build()
}
