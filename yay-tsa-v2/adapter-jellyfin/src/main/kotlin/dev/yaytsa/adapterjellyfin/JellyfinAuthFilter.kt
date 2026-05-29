package dev.yaytsa.adapterjellyfin

import dev.yaytsa.application.auth.AuthQueries
import dev.yaytsa.shared.Hashing
import dev.yaytsa.shared.UserId
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.authentication.AbstractAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

@Component
class JellyfinAuthFilter(
    private val authQueries: AuthQueries,
) : OncePerRequestFilter() {
    override fun shouldNotFilter(request: HttpServletRequest): Boolean {
        val path = request.requestURI
        return !path.startsWith("/Users") &&
            !path.startsWith("/UserFavoriteItems") &&
            !path.startsWith("/UserViews") &&
            !path.startsWith("/Items") &&
            !path.startsWith("/Sessions") &&
            !path.startsWith("/Playlists") &&
            !path.startsWith("/Audio") &&
            !path.startsWith("/Artists") &&
            !path.startsWith("/Search") &&
            !path.startsWith("/System") &&
            !path.startsWith("/Admin") &&
            !path.startsWith("/Karaoke") &&
            !path.startsWith("/Lyrics") &&
            !path.startsWith("/tracks") &&
            !path.startsWith("/v1/")
    }

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        // Try X-Emby-Token header first
        val token =
            request.getHeader("X-Emby-Token")
                ?: extractTokenFromAuth(request.getHeader("X-Emby-Authorization"))
                ?: extractBearerToken(request.getHeader("Authorization"))
                ?: request.getParameter("api_key")
                ?: extractTokenCookie(request.cookies)

        if (token != null && SecurityContextHolder.getContext().authentication == null) {
            val user = authQueries.findByApiToken(token)
            if (user != null && user.isActive) {
                val hashedToken = Hashing.sha256Hex(token)
                val apiToken = user.apiTokens.find { Hashing.constantTimeEquals(it.token, hashedToken) }
                val expired =
                    apiToken?.expiresAt?.let {
                        java.time.Instant
                            .now()
                            .isAfter(it)
                    } ?: false
                if (apiToken != null && !apiToken.revoked && !expired) {
                    SecurityContextHolder.getContext().authentication =
                        JellyfinAuthentication(user.id, user.isAdmin, token, apiToken?.deviceId?.value)
                }
            }
        }
        filterChain.doFilter(request, response)
    }

    private fun extractTokenFromAuth(header: String?): String? {
        if (header == null) return null
        val tokenMatch = Regex("""Token="([^"]+)"""").find(header)
        return tokenMatch?.groupValues?.get(1)
    }

    private fun extractBearerToken(header: String?): String? {
        if (header == null || !header.startsWith("Bearer ")) return null
        return header.removePrefix("Bearer ").trim().ifBlank { null }
    }

    private fun extractTokenCookie(cookies: Array<jakarta.servlet.http.Cookie>?): String? =
        cookies?.firstOrNull { it.name == "yay_token" }?.value?.takeIf { it.isNotBlank() }
}

interface DeviceBoundAuthentication {
    val deviceId: String?
}

class JellyfinAuthentication(
    val userId: UserId,
    val isAdmin: Boolean,
    private val token: String,
    override val deviceId: String? = null,
) : AbstractAuthenticationToken(emptyList()),
    DeviceBoundAuthentication {
    init {
        isAuthenticated = true
    }

    override fun getCredentials(): String = token

    override fun getPrincipal(): UserId = userId

    override fun getName(): String = userId.value
}
