package dev.yaytsa.app.security

import com.github.benmanes.caffeine.cache.Caffeine
import dev.yaytsa.adapterjellyfin.TokenRevokedEvent
import dev.yaytsa.adapterjellyfin.UserSecurityChangedEvent
import dev.yaytsa.application.auth.AuthQueries
import dev.yaytsa.domain.auth.UserAggregate
import dev.yaytsa.shared.Hashing
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import java.time.Duration

/**
 * Short-TTL cache for the per-request token -> user lookup. Audio elements re-request
 * stream URLs with api_key constantly, so an uncached findByApiToken hammers the DB.
 * TTL is intentionally tiny (revocation/logout becomes effective within it); the
 * filter still re-checks revoked/expired on the cached aggregate every request.
 */
@Component
class TokenValidationCache(
    private val authQueries: AuthQueries,
) {
    private val cache =
        Caffeine
            .newBuilder()
            .expireAfterWrite(Duration.ofSeconds(TTL_SECONDS))
            .maximumSize(MAX_ENTRIES)
            .build<String, UserAggregate>()

    fun findByApiToken(token: String): UserAggregate? {
        val key = Hashing.sha256Hex(token)
        cache.getIfPresent(key)?.let { return it }
        val user = authQueries.findByApiToken(token) ?: return null
        cache.put(key, user)
        return user
    }

    fun invalidate(token: String) {
        cache.invalidate(Hashing.sha256Hex(token))
    }

    fun invalidateUser(userId: String) {
        cache.asMap().entries.removeIf { it.value.id.value == userId }
    }

    @EventListener
    fun onTokenRevoked(event: TokenRevokedEvent) {
        invalidate(event.token)
    }

    // Deactivation / password reset don't expose the raw token, so evict every cached entry
    // for the user — otherwise a deactivated or re-credentialed user keeps authenticating for
    // up to the cache TTL.
    @EventListener
    fun onUserSecurityChanged(event: UserSecurityChangedEvent) {
        invalidateUser(event.userId)
    }

    companion object {
        private const val TTL_SECONDS = 10L
        private const val MAX_ENTRIES = 10_000L
    }
}
