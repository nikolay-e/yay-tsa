package dev.yaytsa.app

import dev.yaytsa.shared.Failure
import jakarta.servlet.http.HttpServletRequest
import org.slf4j.LoggerFactory
import org.springframework.dao.DataAccessException
import org.springframework.dao.InvalidDataAccessApiUsageException
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.security.access.AccessDeniedException
import org.springframework.web.HttpRequestMethodNotSupportedException
import org.springframework.web.bind.MissingServletRequestParameterException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException
import org.springframework.web.server.ResponseStatusException
import org.springframework.web.servlet.NoHandlerFoundException
import org.springframework.web.servlet.resource.NoResourceFoundException

class FailureException(
    val failure: Failure,
) : RuntimeException(failure.toString())

@RestControllerAdvice
class GlobalExceptionHandler {
    private val log = LoggerFactory.getLogger(GlobalExceptionHandler::class.java)

    @ExceptionHandler(FailureException::class)
    fun handleFailure(
        ex: FailureException,
        request: HttpServletRequest,
    ): ResponseEntity<Map<String, Any>> = handleDomainFailure(ex.failure, request)

    fun handleDomainFailure(
        failure: Failure,
        request: HttpServletRequest,
    ): ResponseEntity<Map<String, Any>> {
        val (status, title, detail) = mapFailure(failure)
        if (status.is5xxServerError) {
            log.error("Domain failure mapped to {}: {}", status, failure)
        } else {
            log.debug("Domain failure mapped to {}: {}", status, failure)
        }
        return problemDetail(status, title, detail, request)
    }

    @ExceptionHandler(NoHandlerFoundException::class)
    fun handleNotFound(
        ex: NoHandlerFoundException,
        request: HttpServletRequest,
    ): ResponseEntity<Map<String, Any>> = problemDetail(HttpStatus.NOT_FOUND, "Not Found", "Not found: ${ex.requestURL}", request)

    @ExceptionHandler(NoResourceFoundException::class)
    fun handleNoResource(
        ex: NoResourceFoundException,
        request: HttpServletRequest,
    ): ResponseEntity<Map<String, Any>> = problemDetail(HttpStatus.NOT_FOUND, "Not Found", "Not found: ${request.requestURI}", request)

    @ExceptionHandler(AccessDeniedException::class)
    fun handleAccessDenied(
        ex: AccessDeniedException,
        request: HttpServletRequest,
    ): ResponseEntity<Map<String, Any>> = problemDetail(HttpStatus.FORBIDDEN, "Forbidden", "Access denied", request)

    @ExceptionHandler(IllegalArgumentException::class)
    fun handleBadRequest(
        ex: IllegalArgumentException,
        request: HttpServletRequest,
    ): ResponseEntity<Map<String, Any>> {
        log.debug("Bad request: {}", ex.message)
        return problemDetail(HttpStatus.BAD_REQUEST, "Bad Request", ex.message ?: "Bad request", request)
    }

    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun handleUnreadable(
        ex: HttpMessageNotReadableException,
        request: HttpServletRequest,
    ): ResponseEntity<Map<String, Any>> = problemDetail(HttpStatus.BAD_REQUEST, "Bad Request", "Invalid request body", request)

    @ExceptionHandler(MissingServletRequestParameterException::class)
    fun handleMissingParam(
        ex: MissingServletRequestParameterException,
        request: HttpServletRequest,
    ): ResponseEntity<Map<String, Any>> = problemDetail(HttpStatus.BAD_REQUEST, "Bad Request", "Missing required parameter: ${ex.parameterName}", request)

    @ExceptionHandler(HttpRequestMethodNotSupportedException::class)
    fun handleMethodNotAllowed(
        ex: HttpRequestMethodNotSupportedException,
        request: HttpServletRequest,
    ): ResponseEntity<Map<String, Any>> = problemDetail(HttpStatus.METHOD_NOT_ALLOWED, "Method Not Allowed", "Method ${ex.method} not allowed", request)

    @ExceptionHandler(InvalidDataAccessApiUsageException::class)
    fun handleInvalidDataAccess(
        ex: InvalidDataAccessApiUsageException,
        request: HttpServletRequest,
    ): ResponseEntity<Map<String, Any>> {
        log.debug("Invalid data access: {}", ex.message)
        val message = ex.cause?.message ?: ex.message ?: "Invalid request data"
        return problemDetail(HttpStatus.BAD_REQUEST, "Bad Request", message, request)
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException::class)
    fun handleTypeMismatch(
        ex: MethodArgumentTypeMismatchException,
        request: HttpServletRequest,
    ): ResponseEntity<Map<String, Any>> {
        log.debug("Type mismatch for parameter '{}': {}", ex.name, ex.message)
        return problemDetail(HttpStatus.BAD_REQUEST, "Bad Request", "Invalid value for parameter '${ex.name}': ${ex.value}", request)
    }

    @ExceptionHandler(DataAccessException::class)
    fun handleDataAccess(
        ex: DataAccessException,
        request: HttpServletRequest,
    ): ResponseEntity<Map<String, Any>> {
        log.debug("Data access error: {}", ex.message)
        return problemDetail(HttpStatus.BAD_REQUEST, "Bad Request", "Invalid request data", request)
    }

    @ExceptionHandler(ResponseStatusException::class)
    fun handleResponseStatus(
        ex: ResponseStatusException,
        request: HttpServletRequest,
    ): ResponseEntity<Map<String, Any>> {
        val status = HttpStatus.valueOf(ex.statusCode.value())
        return problemDetail(status, status.reasonPhrase, ex.reason ?: "Error", request)
    }

    @ExceptionHandler(Exception::class)
    fun handleGeneric(
        ex: Exception,
        request: HttpServletRequest,
    ): ResponseEntity<Map<String, Any>> {
        log.error("Unhandled exception", ex)
        return problemDetail(HttpStatus.INTERNAL_SERVER_ERROR, "Internal Server Error", "Internal server error", request)
    }

    private fun mapFailure(failure: Failure): Triple<HttpStatus, String, String> =
        when (failure) {
            is Failure.NotFound ->
                Triple(HttpStatus.NOT_FOUND, "Not Found", "${failure.entityType} '${failure.id}' not found")
            is Failure.Unauthorized ->
                Triple(HttpStatus.UNAUTHORIZED, "Unauthorized", failure.reason)
            is Failure.Conflict ->
                Triple(
                    HttpStatus.CONFLICT,
                    "Conflict",
                    "Version conflict: expected ${failure.expected.value}, actual ${failure.actual.value}",
                )
            is Failure.StorageConflict ->
                Triple(
                    HttpStatus.CONFLICT,
                    "Conflict",
                    "Concurrent modification of ${failure.aggregateType} '${failure.id}'",
                )
            is Failure.InvariantViolation ->
                Triple(HttpStatus.BAD_REQUEST, "Bad Request", failure.rule)
            is Failure.UnsupportedByProtocol ->
                Triple(
                    HttpStatus.BAD_REQUEST,
                    "Unsupported By Protocol",
                    "Command '${failure.command}' not supported by protocol '${failure.protocol.value}'",
                )
        }

    private fun problemDetail(
        status: HttpStatus,
        title: String,
        detail: String,
        request: HttpServletRequest,
    ): ResponseEntity<Map<String, Any>> =
        ResponseEntity
            .status(status)
            .contentType(MediaType.APPLICATION_PROBLEM_JSON)
            .body(
                mapOf(
                    "type" to "about:blank",
                    "title" to title,
                    "status" to status.value(),
                    "detail" to detail,
                    "instance" to request.requestURI,
                ),
            )
}
