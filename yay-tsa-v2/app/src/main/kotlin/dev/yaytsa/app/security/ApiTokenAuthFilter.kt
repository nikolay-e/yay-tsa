package dev.yaytsa.app.security

import dev.yaytsa.shared.Hashing
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

@Component
class ApiTokenAuthFilter(
    private val tokenValidationCache: TokenValidationCache,
) : OncePerRequestFilter() {
    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        // /Karaoke/{id}/instrumental and other stream endpoints serve audio via <audio src="">,
        // which can't send Authorization headers — they auth via ?api_key=... query param.
        // Without this, those URLs return 401 unless the browser happens to ship the yay_token
        // cookie too (which it does for same-origin, hiding the bug from manual smoke).
        val token =
            extractBearerToken(request)
                ?: request.getParameter("api_key")?.takeIf { it.isNotBlank() }
                ?: extractTokenCookie(request.cookies)
        if (token != null) {
            val user = tokenValidationCache.findByApiToken(token)
            if (user != null && user.isActive) {
                val hashedToken = Hashing.sha256Hex(token)
                val apiToken = user.apiTokens.find { Hashing.constantTimeEquals(it.token, hashedToken) }
                val now = java.time.Instant.now()
                val expired =
                    apiToken?.expiresAt?.let { !now.isBefore(it) } ?: false
                if (apiToken != null && !apiToken.revoked && !expired) {
                    SecurityContextHolder.getContext().authentication =
                        YaytsaAuthentication(
                            userId = user.id,
                            tokenValue = token,
                            deviceId = apiToken.deviceId?.value,
                            deviceName = apiToken.deviceName,
                            isAdmin = user.isAdmin,
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

    private fun extractTokenCookie(cookies: Array<jakarta.servlet.http.Cookie>?): String? =
        cookies?.firstOrNull { it.name == "yay_token" }?.value?.takeIf { it.isNotBlank() }
}
