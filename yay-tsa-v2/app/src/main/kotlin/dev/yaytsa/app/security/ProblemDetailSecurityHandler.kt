package dev.yaytsa.app.security

import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.core.AuthenticationException
import org.springframework.security.web.AuthenticationEntryPoint
import org.springframework.security.web.access.AccessDeniedHandler
import org.springframework.stereotype.Component

@Component
class ProblemDetailSecurityHandler(
    private val objectMapper: ObjectMapper,
    @org.springframework.beans.factory.annotation.Value("\${yaytsa.oauth.public-base-url:https://yay-tsa.com}")
    private val oauthPublicBaseUrl: String,
) : AuthenticationEntryPoint,
    AccessDeniedHandler {
    override fun commence(
        request: HttpServletRequest,
        response: HttpServletResponse,
        authException: AuthenticationException,
    ) {
        if (request.requestURI == "/mcp" && !response.isCommitted) {
            response.setHeader(
                "WWW-Authenticate",
                "Bearer resource_metadata=\"$oauthPublicBaseUrl/.well-known/oauth-protected-resource/api/mcp\"",
            )
        }
        write(request, response, HttpStatus.UNAUTHORIZED, "Unauthorized", "Authentication required")
    }

    override fun handle(
        request: HttpServletRequest,
        response: HttpServletResponse,
        accessDeniedException: AccessDeniedException,
    ) = write(request, response, HttpStatus.FORBIDDEN, "Forbidden", "Access denied")

    private fun write(
        request: HttpServletRequest,
        response: HttpServletResponse,
        status: HttpStatus,
        title: String,
        detail: String,
    ) {
        if (response.isCommitted) return
        response.status = status.value()
        response.contentType = MediaType.APPLICATION_PROBLEM_JSON_VALUE
        response.characterEncoding = Charsets.UTF_8.name()
        objectMapper.writeValue(
            response.outputStream,
            mapOf(
                "type" to "about:blank",
                "title" to title,
                "status" to status.value(),
                "detail" to detail,
                "instance" to request.requestURI,
            ),
        )
    }
}
