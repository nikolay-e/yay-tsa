package dev.yaytsa.adapterjellyfin

import dev.yaytsa.application.auth.AuthQueries
import dev.yaytsa.application.auth.AuthUseCases
import dev.yaytsa.domain.auth.ApiTokenId
import dev.yaytsa.domain.auth.CreateApiToken
import dev.yaytsa.domain.auth.DeviceId
import dev.yaytsa.shared.CommandContext
import dev.yaytsa.shared.IdempotencyKey
import dev.yaytsa.shared.ProtocolId
import dev.yaytsa.shared.UserId
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
    private val clock: dev.yaytsa.application.shared.port.Clock,
) {
    data class LoginRequest(
        val Username: String,
        val Pw: String,
    )

    @PostMapping("/Users/AuthenticateByName")
    fun login(
        @RequestBody request: LoginRequest,
    ): ResponseEntity<Any> {
        val user =
            authQueries.findByUsername(request.Username)
                ?: return ResponseEntity.status(401).body(mapOf("error" to "Invalid credentials"))

        if (!user.isActive) return ResponseEntity.status(401).body(mapOf("error" to "User disabled"))

        // Verify password against bcrypt hash
        val passwordValid =
            try {
                org.springframework.security.crypto.bcrypt.BCrypt
                    .checkpw(request.Pw, user.passwordHash)
            } catch (_: IllegalArgumentException) {
                false // Malformed hash = auth fails, not bypasses
            }
        if (!passwordValid) {
            return ResponseEntity.status(401).body(mapOf("error" to "Invalid credentials"))
        }

        // Generate a new API token for this session
        val tokenValue = generateToken()
        val tokenId = ApiTokenId(UUID.randomUUID().toString())
        val deviceId = DeviceId("web-${UUID.randomUUID().toString().take(8)}")

        val cmd = CreateApiToken(user.id, tokenId, tokenValue, deviceId, "Web Browser", null)
        val ctx = CommandContext(user.id, ProtocolId("JELLYFIN"), clock.now(), IdempotencyKey(UUID.randomUUID().toString()), user.version)
        authUseCases.execute(cmd, ctx)

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
        if (auth is JellyfinAuthentication) {
            val uid = auth.userId
            val user = authQueries.findUser(uid)
            if (user != null) {
                val tokenHash =
                    java.security.MessageDigest
                        .getInstance("SHA-256")
                        .digest(auth.credentials.toByteArray(Charsets.UTF_8))
                        .joinToString("") { "%02x".format(it) }
                val apiToken = user.apiTokens.find { it.token == tokenHash }
                if (apiToken != null) {
                    val cmd =
                        dev.yaytsa.domain.auth
                            .RevokeApiToken(uid, apiToken.id)
                    val ctx =
                        CommandContext(
                            uid,
                            ProtocolId("JELLYFIN"),
                            clock.now(),
                            IdempotencyKey(UUID.randomUUID().toString()),
                            user.version,
                        )
                    authUseCases.execute(cmd, ctx)
                }
            }
        }
        org.springframework.security.core.context.SecurityContextHolder
            .clearContext()
        return ResponseEntity.noContent().build()
    }

    @GetMapping("/Users/{userId}")
    fun getUser(
        @PathVariable userId: String,
    ): ResponseEntity<Any> {
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
}
