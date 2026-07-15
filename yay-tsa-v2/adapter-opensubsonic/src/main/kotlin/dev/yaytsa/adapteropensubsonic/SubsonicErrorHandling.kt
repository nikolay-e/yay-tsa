package dev.yaytsa.adapteropensubsonic

import jakarta.servlet.http.HttpServletRequest
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.MissingServletRequestParameterException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

class SubsonicApiException(
    val code: Int,
    override val message: String,
) : RuntimeException(message)

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(basePackages = ["dev.yaytsa.adapteropensubsonic"])
class SubsonicExceptionAdvice(
    private val responseWriter: SubsonicResponseWriter,
) {
    private val log = org.slf4j.LoggerFactory.getLogger(javaClass)

    @ExceptionHandler(SubsonicApiException::class)
    fun handleSubsonicApiException(
        e: SubsonicApiException,
        request: HttpServletRequest,
    ): ResponseEntity<String> = responseWriter.write(error(e.code, e.message), request.getParameter("f"))

    @ExceptionHandler(MissingServletRequestParameterException::class)
    fun handleMissingParameter(
        e: MissingServletRequestParameterException,
        request: HttpServletRequest,
    ): ResponseEntity<String> = responseWriter.write(error(10, "Required parameter is missing: ${e.parameterName}"), request.getParameter("f"))

    @ExceptionHandler(Exception::class)
    fun handleUnexpected(
        e: Exception,
        request: HttpServletRequest,
    ): ResponseEntity<String> {
        log.error("Unhandled Subsonic API error on {}", request.requestURI, e)
        return responseWriter.write(error(0, "Internal error"), request.getParameter("f"))
    }
}
