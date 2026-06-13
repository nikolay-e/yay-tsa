package dev.yaytsa.adapteropensubsonic

import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import dev.yaytsa.application.auth.AuthQueries
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
        val token = request.getParameter("t")
        val salt = request.getParameter("s")
        val password = request.getParameter("p")

        when {
            username == null || (password == null && (token == null || salt == null)) ->
                reject(request, response, 10, "Required authentication parameter is missing")
            password == null ->
                reject(request, response, 41, "Token authentication is not supported for this user, use password authentication")
            else -> {
                val userId = authenticate(username, password)
                if (userId == null) {
                    reject(request, response, 40, "Wrong username or password")
                } else {
                    SecurityContextHolder.getContext().authentication = SubsonicAuthentication(userId, username)
                    filterChain.doFilter(request, response)
                }
            }
        }
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
