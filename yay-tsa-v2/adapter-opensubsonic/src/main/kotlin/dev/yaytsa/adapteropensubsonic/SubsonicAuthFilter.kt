package dev.yaytsa.adapteropensubsonic

import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import dev.yaytsa.application.auth.AuthQueries
import dev.yaytsa.application.shared.port.Clock
import dev.yaytsa.shared.Hashing
import dev.yaytsa.shared.UserId
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.authentication.AbstractAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.crypto.bcrypt.BCrypt
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import java.time.Duration

@Component
class SubsonicAuthFilter(
    private val authQueries: AuthQueries,
    private val responseWriter: SubsonicResponseWriter,
    private val clock: Clock,
) : OncePerRequestFilter() {
    private val verifiedCredentials: Cache<String, UserId> =
        Caffeine
            .newBuilder()
            .expireAfterWrite(Duration.ofSeconds(60))
            .maximumSize(10_000)
            .build()

    override fun shouldNotFilter(request: HttpServletRequest): Boolean = !request.requestURI.startsWith("/rest/")

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val username = request.getParameter("u")
        val apiKey = request.getParameter("apiKey")
        val token = request.getParameter("t")
        val salt = request.getParameter("s")
        val password = request.getParameter("p")

        when {
            apiKey != null -> authenticateWithApiKey(request, response, filterChain, apiKey)
            username == null || (password == null && (token == null || salt == null)) ->
                reject(request, response, 10, "Required authentication parameter is missing")
            password == null ->
                reject(request, response, 41, "Token authentication is not supported for this user, use password authentication")
            else -> {
                val userId = authenticate(username, password)
                if (userId == null) {
                    reject(request, response, 40, "Wrong username or password")
                } else {
                    proceedAs(request, response, filterChain, userId, username)
                }
            }
        }
    }

    private fun authenticateWithApiKey(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
        apiKey: String,
    ) {
        val user = authenticateApiKey(apiKey)
        if (user == null) {
            reject(request, response, 44, "Invalid API key")
        } else {
            proceedAs(request, response, filterChain, user.id, user.username)
        }
    }

    private fun authenticateApiKey(apiKey: String): ApiKeyPrincipal? {
        if (apiKey.isBlank()) return null
        val user = authQueries.findByApiToken(apiKey) ?: return null
        if (!user.isActive) return null
        val hashedToken = Hashing.sha256Hex(apiKey)
        val apiToken = user.apiTokens.find { Hashing.constantTimeEquals(it.token, hashedToken) } ?: return null
        if (apiToken.revoked) return null
        val expired = apiToken.expiresAt?.let { clock.now().isAfter(it) } ?: false
        if (expired) return null
        return ApiKeyPrincipal(user.id, user.username)
    }

    private fun proceedAs(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
        userId: UserId,
        username: String,
    ) {
        SecurityContextHolder.getContext().authentication = SubsonicAuthentication(userId, username)
        filterChain.doFilter(request, response)
    }

    private fun reject(
        request: HttpServletRequest,
        response: HttpServletResponse,
        code: Int,
        message: String,
    ) {
        responseWriter.writeTo(response, error(code, message), request.getParameter("f"), request.getParameter("callback"))
    }

    private fun authenticate(
        username: String,
        password: String,
    ): UserId? {
        val plaintext =
            if (password.startsWith("enc:")) {
                decodeHex(password.removePrefix("enc:")) ?: return null
            } else {
                password
            }
        val cacheKey = sha256("$username:$plaintext")
        verifiedCredentials.getIfPresent(cacheKey)?.let { return it }

        val user = authQueries.findByUsername(username) ?: return null
        if (!user.isActive) return null
        if (!verifyBcrypt(plaintext, user.passwordHash)) return null
        verifiedCredentials.put(cacheKey, user.id)
        return user.id
    }

    private fun verifyBcrypt(
        plaintext: String,
        storedHash: String,
    ): Boolean =
        try {
            BCrypt.checkpw(plaintext, storedHash)
        } catch (_: IllegalArgumentException) {
            false
        }

    private fun decodeHex(hex: String): String? = Hashing.hexDecode(hex)?.let { String(it, Charsets.UTF_8) }

    private fun sha256(input: String): String = Hashing.sha256Hex(input)
}

private data class ApiKeyPrincipal(
    val id: UserId,
    val username: String,
)

class SubsonicAuthentication(
    val userId: UserId,
    private val username: String,
) : AbstractAuthenticationToken(emptyList()) {
    init {
        isAuthenticated = true
    }

    override fun getCredentials(): Any = ""

    override fun getPrincipal(): UserId = userId

    override fun getName(): String = userId.value
}
