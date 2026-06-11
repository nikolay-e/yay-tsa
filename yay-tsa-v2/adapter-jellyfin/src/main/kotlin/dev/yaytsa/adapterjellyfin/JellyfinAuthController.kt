package dev.yaytsa.adapterjellyfin

import dev.yaytsa.adaptershared.AdapterCommandContextFactory
import dev.yaytsa.adaptershared.problemDetail
import dev.yaytsa.application.auth.AuthQueries
import dev.yaytsa.application.auth.AuthUseCases
import dev.yaytsa.domain.auth.ApiTokenId
import dev.yaytsa.domain.auth.CreateApiToken
import dev.yaytsa.domain.auth.RevokeApiToken
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
        val tokenValue = generateToken()
        val tokenId = ApiTokenId(UUID.randomUUID().toString())
        val deviceId = DeviceId("web-${UUID.randomUUID().toString().take(8)}")

        var tokenPersisted = false
        repeat(2) { attempt ->
            if (tokenPersisted) return@repeat
            val freshUser =
                if (attempt == 0) user else authQueries.findByUsername(request.username) ?: user
            val cmd = CreateApiToken(freshUser.id, tokenId, tokenValue, deviceId, "Web Browser", null)
            val ctx = ctxFactory.create(freshUser.id, freshUser.version)
            when (val result = authUseCases.execute(cmd, ctx)) {
                is CommandResult.Success -> tokenPersisted = true
                is CommandResult.Failed -> log.warn("CreateApiToken attempt {} failed for user {}: {}", attempt + 1, freshUser.id.value, result.failure)
            }
        }
        if (!tokenPersisted) {
            return problemDetail(HttpStatus.SERVICE_UNAVAILABLE, "Service Unavailable", "Could not create session token, please retry")
        }

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
        principal: Principal,
    ): ResponseEntity<Any> {
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

    private fun generateToken(): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
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
