package dev.yaytsa.adaptermcp

import dev.yaytsa.shared.Hashing
import org.springframework.stereotype.Component
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class AuthorizationCodeEntry(
    val clientId: UUID,
    val redirectUri: String,
    val codeChallenge: String,
    val userId: String,
    val scope: String?,
    val expiresAt: Instant,
)

@Component
class OAuthAuthorizationCodeStore {
    private val secureRandom = SecureRandom()
    private val codes = ConcurrentHashMap<String, AuthorizationCodeEntry>()

    fun issue(
        clientId: UUID,
        redirectUri: String,
        codeChallenge: String,
        userId: String,
        scope: String?,
    ): String? {
        val now = Instant.now()
        codes.entries.removeIf { it.value.expiresAt.isBefore(now) }
        if (codes.size >= MAX_PENDING_CODES) return null
        val code = generateCode()
        codes[code] = AuthorizationCodeEntry(clientId, redirectUri, codeChallenge, userId, scope, now.plus(CODE_TTL))
        return code
    }

    fun redeem(code: String): AuthorizationCodeEntry? {
        val entry = codes.remove(code) ?: return null
        return if (entry.expiresAt.isBefore(Instant.now())) null else entry
    }

    private fun generateCode(): String {
        val bytes = ByteArray(32)
        secureRandom.nextBytes(bytes)
        return Hashing.hexEncode(bytes)
    }

    companion object {
        private val CODE_TTL = Duration.ofMinutes(10)
        private const val MAX_PENDING_CODES = 10_000
    }
}
