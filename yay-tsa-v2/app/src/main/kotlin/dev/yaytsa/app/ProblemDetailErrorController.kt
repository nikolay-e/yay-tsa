package dev.yaytsa.app

import io.swagger.v3.oas.annotations.Hidden
import jakarta.servlet.RequestDispatcher
import jakarta.servlet.http.HttpServletRequest
import org.springframework.boot.web.servlet.error.ErrorController
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@Hidden
@RestController
class ProblemDetailErrorController : ErrorController {
    @RequestMapping("/error")
    fun handleError(request: HttpServletRequest): ResponseEntity<Map<String, Any>> {
        val statusCode = (request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE) as? Int) ?: HttpStatus.INTERNAL_SERVER_ERROR.value()
        val status = runCatching { HttpStatus.valueOf(statusCode) }.getOrDefault(HttpStatus.INTERNAL_SERVER_ERROR)
        val path = (request.getAttribute(RequestDispatcher.ERROR_REQUEST_URI) as? String) ?: request.requestURI
        return ResponseEntity
            .status(status)
            .contentType(MediaType.APPLICATION_PROBLEM_JSON)
            .body(
                mapOf(
                    "type" to "about:blank",
                    "title" to status.reasonPhrase,
                    "status" to status.value(),
                    "detail" to status.reasonPhrase,
                    "instance" to path,
                ),
            )
    }
}
