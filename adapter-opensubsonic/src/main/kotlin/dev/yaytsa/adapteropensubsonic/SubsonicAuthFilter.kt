package dev.yaytsa.adapteropensubsonic

import dev.yaytsa.application.auth.AuthQueries
import dev.yaytsa.shared.UserId
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.authentication.AbstractAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

@Component
class SubsonicAuthFilter(
    private val authQueries: AuthQueries,
) : OncePerRequestFilter() {
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

        if (username == null) {
            filterChain.doFilter(request, response)
            return
        }

        val user = authQueries.findByUsername(username)
        if (user != null && user.isActive) {
            val authenticated =
                when {
                    // Password mode: p=plaintext or p=enc:hex-encoded password
                    password != null -> {
                        val plain =
                            if (password.startsWith("enc:")) {
                                decodeHex(password.removePrefix("enc:"))
                            } else {
                                password
                            }
                        verifyPassword(plain, user.passwordHash)
                    }
                    // Token mode: t=md5(password+s) — cannot verify against bcrypt hash.
                    // Would require a separate subsonic-specific plaintext password per user.
                    token != null && salt != null -> false
                    else -> false
                }
            if (authenticated) {
                SecurityContextHolder.getContext().authentication =
                    SubsonicAuthentication(user.id, username)
            }
        }
        filterChain.doFilter(request, response)
    }

    /**
     * Verify a plaintext password against the stored hash.
     * Supports bcrypt hashes (prefix `$2a$`, `$2b$`, `$2y$`) and legacy MD5 hashes.
     */
    private fun verifyPassword(
        plaintext: String,
        storedHash: String,
    ): Boolean =
        when {
            storedHash.startsWith("\$2a\$") ||
                storedHash.startsWith("\$2b\$") ||
                storedHash.startsWith("\$2y\$") ->
                try {
                    org.springframework.security.crypto.bcrypt.BCrypt
                        .checkpw(plaintext, storedHash)
                } catch (_: IllegalArgumentException) {
                    false
                }
            else -> md5(plaintext) == storedHash || plaintext == storedHash
        }

    private fun decodeHex(hex: String): String {
        val bytes = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        return String(bytes, Charsets.UTF_8)
    }

    private fun md5(input: String): String {
        val digest = java.security.MessageDigest.getInstance("MD5")
        val hash = digest.digest(input.toByteArray(Charsets.UTF_8))
        return hash.joinToString("") { "%02x".format(it) }
    }
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
