package dev.yaytsa.app.security

import dev.yaytsa.application.auth.AuthQueries
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

@Component
class ApiTokenAuthFilter(
    private val authQueries: AuthQueries,
) : OncePerRequestFilter() {
    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val token = extractBearerToken(request)
        if (token != null) {
            val user = authQueries.findByApiToken(token)
            if (user != null && user.isActive) {
                val hashedToken =
                    java.security.MessageDigest
                        .getInstance("SHA-256")
                        .digest(token.toByteArray(Charsets.UTF_8))
                        .joinToString("") { "%02x".format(it) }
                val apiToken = user.apiTokens.find { it.token == hashedToken }
                val expired =
                    apiToken?.expiresAt?.let {
                        java.time.Instant
                            .now()
                            .isAfter(it)
                    } ?: false
                if (!expired) {
                    SecurityContextHolder.getContext().authentication =
                        YaytsaAuthentication(
                            userId = user.id,
                            tokenValue = token,
                        )
                }
            }
        }
        filterChain.doFilter(request, response)
    }

    private fun extractBearerToken(request: HttpServletRequest): String? {
        val header = request.getHeader("Authorization") ?: return null
        return if (header.startsWith("Bearer ")) header.substring(7) else null
    }
}
