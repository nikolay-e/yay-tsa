package dev.yaytsa.adapterjellyfin

import dev.yaytsa.adaptershared.AdapterCommandContextFactory
import dev.yaytsa.adaptershared.problemDetail
import dev.yaytsa.application.auth.AuthQueries
import dev.yaytsa.application.auth.AuthUseCases
import dev.yaytsa.domain.auth.ApiTokenId
import dev.yaytsa.domain.auth.ChangePassword
import dev.yaytsa.domain.auth.CreateApiToken
import dev.yaytsa.domain.auth.RevokeApiToken
import dev.yaytsa.domain.auth.UserAggregate
import dev.yaytsa.shared.CommandResult
import dev.yaytsa.shared.DeviceId
import dev.yaytsa.shared.Hashing
import dev.yaytsa.shared.UserId
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import java.security.Principal
import java.security.SecureRandom
import java.util.UUID

@RestController
class JellyfinAuthController(
    private val authQueries: AuthQueries,
    private val authUseCases: AuthUseCases,
    private val eventPublisher: org.springframework.context.ApplicationEventPublisher,
    @Qualifier("jellyfinCommandContextFactory")
    private val ctxFactory: AdapterCommandContextFactory,
) {
    private val secureRandom = SecureRandom()

    data class LoginRequest(
        @com.fasterxml.jackson.annotation.JsonProperty("Username")
        @com.fasterxml.jackson.annotation.JsonAlias("username")
        val username: String,
        @com.fasterxml.jackson.annotation.JsonProperty("Pw")
        @com.fasterxml.jackson.annotation.JsonAlias("pw")
        val pw: String,
    )

    @PostMapping("/Users/AuthenticateByName")
    fun login(
        @RequestBody request: LoginRequest,
    ): ResponseEntity<Any> {
        val user =
            authQueries.findByUsername(request.username)
                ?: return problemDetail(HttpStatus.UNAUTHORIZED, "Unauthorized", "Invalid credentials")

        if (!user.isActive) return problemDetail(HttpStatus.UNAUTHORIZED, "Unauthorized", "User disabled")

        // Verify password against bcrypt hash
        val passwordValid =
            try {
                org.springframework.security.crypto.bcrypt.BCrypt
                    .checkpw(request.pw, user.passwordHash)
            } catch (_: IllegalArgumentException) {
                false // Malformed hash = auth fails, not bypasses
            }
        if (!passwordValid) {
            return problemDetail(HttpStatus.UNAUTHORIZED, "Unauthorized", "Invalid credentials")
        }

        // Generate a new API token for this session
        val deviceId = DeviceId("web-${UUID.randomUUID().toString().take(8)}")
        val tokenValue =
            issueTokenForUser(user, deviceId)
                ?: return problemDetail(HttpStatus.SERVICE_UNAVAILABLE, "Service Unavailable", "Could not create session token, please retry")

        return ResponseEntity.ok(
            AuthResponse(
                user =
                    MediaServerUser(
                        id = user.id.value,
                        name = user.username,
                        policy = UserPolicy(isAdministrator = user.isAdmin, isDisabled = !user.isActive),
                    ),
                sessionInfo = SessionInfo(id = UUID.randomUUID().toString(), userId = user.id.value, deviceId = deviceId.value),
                accessToken = tokenValue,
            ),
        )
    }

    @PostMapping("/Sessions/Logout")
    @org.springframework.web.bind.annotation.ResponseStatus(HttpStatus.NO_CONTENT)
    fun logout(principal: Principal): ResponseEntity<Void> {
        val auth =
            org.springframework.security.core.context.SecurityContextHolder
                .getContext()
                .authentication
        val rawToken = auth?.credentials?.toString()
        val uidValue = auth?.name
        if (auth?.isAuthenticated == true && !rawToken.isNullOrBlank() && !uidValue.isNullOrBlank()) {
            val uid = UserId(uidValue)
            revokeTokenForUser(uid, rawToken)
        }
        org.springframework.security.core.context.SecurityContextHolder
            .clearContext()
        return ResponseEntity.noContent().build()
    }

    @GetMapping("/Users/{userId}")
    fun getUser(
        @PathVariable userId: String,
        principal: Principal?,
    ): ResponseEntity<Any> {
        if (principal == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()
        }
        if (principal.name != userId && authQueries.findUser(UserId(principal.name))?.isAdmin != true) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build()
        }
        val user =
            authQueries.findUser(UserId(userId))
                ?: return ResponseEntity.notFound().build()
        return ResponseEntity.ok(
            MediaServerUser(
                id = user.id.value,
                name = user.username,
                policy = UserPolicy(isAdministrator = user.isAdmin, isDisabled = !user.isActive),
            ),
        )
    }

    @GetMapping("/Users/Me")
    fun getCurrentUser(principal: Principal): ResponseEntity<Any> {
        val user = authQueries.findUser(UserId(principal.name)) ?: return ResponseEntity.status(401).build()
        return ResponseEntity.ok(
            MediaServerUser(
                id = user.id.value,
                name = user.username,
                policy = UserPolicy(isAdministrator = user.isAdmin, isDisabled = !user.isActive),
            ),
        )
    }

    @GetMapping("/System/Info/Public")
    fun serverInfoPublic(): ResponseEntity<ServerInfo> = ResponseEntity.ok(ServerInfo())

    @GetMapping("/System/Info")
    fun serverInfo(principal: Principal): ResponseEntity<Any> =
        ResponseEntity.ok(
            mapOf(
                "ServerName" to "Yaytsa",
                "Version" to "0.1.0",
                "Id" to "yaytsa",
                "StartupWizardCompleted" to true,
                "OperatingSystem" to System.getProperty("os.name"),
                "HasPendingRestart" to false,
                "IsShuttingDown" to false,
                "CanSelfRestart" to false,
                "CanLaunchWebBrowser" to false,
                "HasUpdateAvailable" to false,
            ),
        )

    @GetMapping("/System/Ping")
    fun pingGet(): ResponseEntity<String> = ResponseEntity.ok("\"Yaytsa\"")

    @PostMapping("/System/Ping")
    fun pingPost(): ResponseEntity<String> = ResponseEntity.ok("\"Yaytsa\"")

    data class ChangePasswordRequest(
        @com.fasterxml.jackson.annotation.JsonProperty("CurrentPw")
        @com.fasterxml.jackson.annotation.JsonAlias("currentPw")
        val currentPw: String,
        @com.fasterxml.jackson.annotation.JsonProperty("NewPw")
        @com.fasterxml.jackson.annotation.JsonAlias("newPw")
        val newPw: String,
    )

    /**
     * Self-service password change for the authenticated user.
     *
     * The [ChangePassword] handler revokes every existing API token (so old
     * bearer tokens on other devices can no longer authenticate, OWASP ASVS).
     * That includes the caller's current token, which would log the caller out
     * on the device they just used. To avoid that, after the change we issue a
     * fresh token for a new device entry and return it as AccessToken so the
     * client can swap it in and continue seamlessly.
     *
     * A wrong CurrentPw returns 403 (not 401) deliberately: the caller is still
     * authenticated, and a 401 would trip the client's global auth-error handler
     * and force a full logout.
     */
    @PostMapping("/Users/Password")
    fun changePassword(
        principal: Principal,
        @RequestBody request: ChangePasswordRequest,
    ): ResponseEntity<Any> {
        val uid = UserId(principal.name)
        val user =
            authQueries.findUser(uid)
                ?: return problemDetail(HttpStatus.UNAUTHORIZED, "Unauthorized", "User not found")

        val currentValid =
            try {
                org.springframework.security.crypto.bcrypt.BCrypt
                    .checkpw(request.currentPw, user.passwordHash)
            } catch (_: IllegalArgumentException) {
                false
            }
        if (!currentValid) {
            return problemDetail(HttpStatus.FORBIDDEN, "Forbidden", "Current password is incorrect")
        }

        if (request.newPw.length < MIN_PASSWORD_LENGTH) {
            return problemDetail(HttpStatus.BAD_REQUEST, "Bad Request", "New password must be at least $MIN_PASSWORD_LENGTH characters")
        }

        val newHash =
            org.springframework.security.crypto.bcrypt.BCrypt
                .hashpw(
                    request.newPw,
                    org.springframework.security.crypto.bcrypt.BCrypt
                        .gensalt(BCRYPT_COST),
                )
        val cmd = ChangePassword(uid, newHash)
        val ctx = ctxFactory.create(uid, user.version)
        when (val result = authUseCases.execute(cmd, ctx)) {
            is CommandResult.Success -> Unit
            is CommandResult.Failed ->
                return problemDetail(HttpStatus.CONFLICT, "Conflict", "Password change failed: ${result.failure}")
        }

        // Every token (including the caller's) is now revoked. Evict cached
        // token validations so they fail immediately on the next request.
        eventPublisher.publishEvent(UserSecurityChangedEvent(uid.value))

        // Reissue a token so the caller's session survives the change. Load the
        // post-change aggregate to pick up the bumped version for the next write.
        val updatedUser = authQueries.findUser(uid) ?: user
        val deviceId = DeviceId("web-${UUID.randomUUID().toString().take(8)}")
        val newToken =
            issueTokenForUser(updatedUser, deviceId)
                ?: return problemDetail(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "Service Unavailable",
                    "Password changed but session token could not be reissued; please log in again",
                )

        return ResponseEntity.ok(mapOf("AccessToken" to newToken))
    }

    /**
     * Persists a fresh API token for [user] on [deviceId] and returns its raw
     * value, or null if persistence failed after retries. Retries once on OCC
     * conflict because concurrent logins from other devices bump the version.
     */
    private fun issueTokenForUser(
        user: UserAggregate,
        deviceId: DeviceId,
    ): String? {
        val tokenValue = generateToken()
        val tokenId = ApiTokenId(UUID.randomUUID().toString())

        repeat(2) { attempt ->
            val freshUser = if (attempt == 0) user else authQueries.findUser(user.id) ?: user
            val cmd = CreateApiToken(freshUser.id, tokenId, tokenValue, deviceId, "Web Browser", null)
            val ctx = ctxFactory.create(freshUser.id, freshUser.version)
            when (val result = authUseCases.execute(cmd, ctx)) {
                is CommandResult.Success -> return tokenValue
                is CommandResult.Failed -> log.warn("CreateApiToken attempt {} failed for user {}: {}", attempt + 1, freshUser.id.value, result.failure)
            }
        }
        return null
    }

    private fun generateToken(): String {
        val bytes = ByteArray(32)
        secureRandom.nextBytes(bytes)
        return Hashing.hexEncode(bytes)
    }

    private fun revokeTokenForUser(
        uid: UserId,
        rawToken: String,
    ) {
        // Retry once on OCC conflict — token write surface gets bumped by every
        // login from other devices, so a single attempt under load races losing.
        repeat(2) {
            val user = authQueries.findUser(uid) ?: return
            val tokenHash = Hashing.sha256Hex(rawToken)
            val apiToken = user.apiTokens.find { it.token == tokenHash && !it.revoked } ?: return
            val cmd = RevokeApiToken(uid, apiToken.id)
            val ctx = ctxFactory.create(uid, user.version)
            val result = authUseCases.execute(cmd, ctx)
            if (result is CommandResult.Success) {
                // Evict any cached token->user validation so the revoked token fails on next request.
                eventPublisher.publishEvent(TokenRevokedEvent(rawToken))
                return
            }
            log.warn("RevokeApiToken attempt failed for user {}: {}", uid.value, result)
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(JellyfinAuthController::class.java)

        // OWASP 2025/2026 Password Storage guidance: bcrypt work factor >= 12 (13 chosen for headroom).
        private const val BCRYPT_COST = 13
        private const val MIN_PASSWORD_LENGTH = 8
    }
}

/** Published when an API token is revoked so caches can evict it immediately. */
data class TokenRevokedEvent(
    val token: String,
)

/**
 * Published when a user's security state changes without exposing a raw token
 * (deactivation, password reset) so token caches can evict all of the user's entries.
 */
data class UserSecurityChangedEvent(
    val userId: String,
)
