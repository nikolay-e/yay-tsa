package dev.yaytsa.app

import org.slf4j.LoggerFactory
import org.springframework.dao.DataAccessException
import org.springframework.dao.InvalidDataAccessApiUsageException
import org.springframework.http.HttpStatus
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

@RestControllerAdvice
class GlobalExceptionHandler {
    private val log = LoggerFactory.getLogger(GlobalExceptionHandler::class.java)

    @ExceptionHandler(NoHandlerFoundException::class)
    fun handleNotFound(ex: NoHandlerFoundException): ResponseEntity<Map<String, Any>> = errorResponse(HttpStatus.NOT_FOUND, "Not found: ${ex.requestURL}")

    @ExceptionHandler(AccessDeniedException::class)
    fun handleAccessDenied(ex: AccessDeniedException): ResponseEntity<Map<String, Any>> = errorResponse(HttpStatus.FORBIDDEN, "Access denied")

    @ExceptionHandler(IllegalArgumentException::class)
    fun handleBadRequest(ex: IllegalArgumentException): ResponseEntity<Map<String, Any>> {
        log.debug("Bad request: {}", ex.message)
        return errorResponse(HttpStatus.BAD_REQUEST, ex.message ?: "Bad request")
    }

    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun handleUnreadable(ex: HttpMessageNotReadableException): ResponseEntity<Map<String, Any>> = errorResponse(HttpStatus.BAD_REQUEST, "Invalid request body")

    @ExceptionHandler(MissingServletRequestParameterException::class)
    fun handleMissingParam(ex: MissingServletRequestParameterException): ResponseEntity<Map<String, Any>> =
        errorResponse(HttpStatus.BAD_REQUEST, "Missing required parameter: ${ex.parameterName}")

    @ExceptionHandler(HttpRequestMethodNotSupportedException::class)
    fun handleMethodNotAllowed(ex: HttpRequestMethodNotSupportedException): ResponseEntity<Map<String, Any>> =
        errorResponse(HttpStatus.METHOD_NOT_ALLOWED, "Method ${ex.method} not allowed")

    @ExceptionHandler(InvalidDataAccessApiUsageException::class)
    fun handleInvalidDataAccess(ex: InvalidDataAccessApiUsageException): ResponseEntity<Map<String, Any>> {
        log.debug("Invalid data access: {}", ex.message)
        val message = ex.cause?.message ?: ex.message ?: "Invalid request data"
        return errorResponse(HttpStatus.BAD_REQUEST, message)
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException::class)
    fun handleTypeMismatch(ex: MethodArgumentTypeMismatchException): ResponseEntity<Map<String, Any>> {
        log.debug("Type mismatch for parameter '{}': {}", ex.name, ex.message)
        return errorResponse(HttpStatus.BAD_REQUEST, "Invalid value for parameter '${ex.name}': ${ex.value}")
    }

    @ExceptionHandler(DataAccessException::class)
    fun handleDataAccess(ex: DataAccessException): ResponseEntity<Map<String, Any>> {
        log.debug("Data access error: {}", ex.message)
        return errorResponse(HttpStatus.BAD_REQUEST, "Invalid request data")
    }

    @ExceptionHandler(ResponseStatusException::class)
    fun handleResponseStatus(ex: ResponseStatusException): ResponseEntity<Map<String, Any>> =
        errorResponse(HttpStatus.valueOf(ex.statusCode.value()), ex.reason ?: "Error")

    @ExceptionHandler(Exception::class)
    fun handleGeneric(ex: Exception): ResponseEntity<Map<String, Any>> {
        log.error("Unhandled exception", ex)
        return errorResponse(HttpStatus.INTERNAL_SERVER_ERROR, "Internal server error")
    }

    private fun errorResponse(
        status: HttpStatus,
        message: String,
    ): ResponseEntity<Map<String, Any>> =
        ResponseEntity.status(status).body(
            mapOf(
                "error" to message,
                "status" to status.value(),
            ),
        )
}
