package dev.yaytsa.adapterjellyfin

import dev.yaytsa.adaptershared.AdapterCommandContextFactory
import dev.yaytsa.adaptershared.problemDetail
import dev.yaytsa.application.auth.AuthQueries
import dev.yaytsa.application.auth.AuthUseCases
import dev.yaytsa.domain.auth.CreateUser
import dev.yaytsa.shared.AggregateVersion
import dev.yaytsa.shared.CommandResult
import dev.yaytsa.shared.UserId
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/Admin")
class JellyfinAdminController(
    private val authUseCases: AuthUseCases,
    private val authQueries: AuthQueries,
    private val eventPublisher: org.springframework.context.ApplicationEventPublisher,
    @Qualifier("jellyfinCommandContextFactory")
    private val ctxFactory: AdapterCommandContextFactory,
) {
    private fun requireAdmin(): ResponseEntity<Any>? {
        val auth = SecurityContextHolder.getContext().authentication
        val callerIsAdmin =
            when {
                auth is JellyfinAuthentication -> auth.isAdmin
                auth != null && auth.isAuthenticated -> authQueries.findUser(UserId(auth.name))?.isAdmin == true
                else -> false
            }
        return if (!callerIsAdmin) problemDetail(HttpStatus.FORBIDDEN, "Forbidden", "Admin role required") else null
    }

    @GetMapping("/Users")
    fun listUsers(): ResponseEntity<Any> {
        requireAdmin()?.let { return it }
        val users = authQueries.listAll()
        return ResponseEntity.ok(
            users.map { u ->
                mapOf(
                    "Id" to u.id.value,
                    "Username" to u.username,
                    "DisplayName" to u.displayName,
                    "IsAdmin" to u.isAdmin,
                    "IsActive" to u.isActive,
                    "CreatedAt" to u.createdAt.toString(),
                    "LastLoginAt" to u.lastLoginAt?.toString(),
                )
            },
        )
    }

    data class CreateUserRequest(
        @com.fasterxml.jackson.annotation.JsonProperty("Username")
        @com.fasterxml.jackson.annotation.JsonAlias("username")
        val username: String,
        @com.fasterxml.jackson.annotation.JsonProperty("DisplayName")
        @com.fasterxml.jackson.annotation.JsonAlias("displayName")
        val displayName: String? = null,
        @com.fasterxml.jackson.annotation.JsonProperty("IsAdmin")
        @com.fasterxml.jackson.annotation.JsonAlias("isAdmin")
        val isAdmin: Boolean = false,
    )

    @PostMapping("/Users")
    fun createUser(
        @RequestBody request: CreateUserRequest,
    ): ResponseEntity<Any> {
        requireAdmin()?.let { return it }
        val uid = UserId(UUID.randomUUID().toString())
        val initialPassword = UUID.randomUUID().toString().take(12)
        val passwordHash =
            org.springframework.security.crypto.bcrypt.BCrypt
                .hashpw(
                    initialPassword,
                    org.springframework.security.crypto.bcrypt.BCrypt
                        .gensalt(BCRYPT_COST),
                )
        val cmd = CreateUser(uid, request.username, passwordHash, request.displayName, null, request.isAdmin)
        val ctx = ctxFactory.create(uid, AggregateVersion.INITIAL)
        return when (val result = authUseCases.execute(cmd, ctx)) {
            is CommandResult.Success ->
                ResponseEntity.ok(
                    mapOf(
                        "user" to
                            mapOf(
                                "Id" to uid.value,
                                "Username" to request.username,
                                "DisplayName" to request.displayName,
                                "IsAdmin" to request.isAdmin,
                                "IsActive" to true,
                            ),
                        "initialPassword" to initialPassword,
                    ),
                )
            is CommandResult.Failed -> problemDetail(HttpStatus.BAD_REQUEST, "Bad Request", result.failure.toString())
        }
    }

    @DeleteMapping("/Users/{userId}")
    fun deleteUser(
        @PathVariable userId: String,
    ): ResponseEntity<Any> {
        requireAdmin()?.let { return it }
        val uid = UserId(userId)
        val user = authQueries.findUser(uid) ?: return ResponseEntity.notFound().build()
        val cmd =
            dev.yaytsa.domain.auth
                .DeactivateUser(uid)
        val ctx = ctxFactory.create(uid, user.version)
        authUseCases.execute(cmd, ctx)
        // Evict cached token validations so the deactivated user is rejected on the next request.
        eventPublisher.publishEvent(UserSecurityChangedEvent(uid.value))
        return ResponseEntity.noContent().build()
    }

    @PostMapping("/Users/{userId}/ResetPassword")
    fun resetPassword(
        @PathVariable userId: String,
    ): ResponseEntity<Any> {
        requireAdmin()?.let { return it }
        val uid = UserId(userId)
        val user = authQueries.findUser(uid) ?: return ResponseEntity.notFound().build()
        val newPassword = UUID.randomUUID().toString().take(12)
        val newHash =
            org.springframework.security.crypto.bcrypt.BCrypt
                .hashpw(
                    newPassword,
                    org.springframework.security.crypto.bcrypt.BCrypt
                        .gensalt(BCRYPT_COST),
                )
        val cmd =
            dev.yaytsa.domain.auth
                .ChangePassword(uid, newHash)
        val ctx = ctxFactory.create(uid, user.version)
        authUseCases.execute(cmd, ctx)
        // Evict cached token validations so old sessions can't keep authenticating post-reset.
        eventPublisher.publishEvent(UserSecurityChangedEvent(uid.value))
        return ResponseEntity.ok(mapOf("newPassword" to newPassword))
    }

    @GetMapping("/Cache/Stats")
    fun cacheStats(): ResponseEntity<Any> {
        requireAdmin()?.let { return it }
        return ResponseEntity.ok(mapOf("imageCache" to mapOf("size" to 0, "hitCount" to 0, "missCount" to 0, "hitRate" to 0.0)))
    }

    @DeleteMapping("/Cache")
    fun clearCache(): ResponseEntity<Any> {
        requireAdmin()?.let { return it }
        return ResponseEntity.ok(mapOf("cleared" to true, "entriesCleared" to 0))
    }

    @DeleteMapping("/Cache/Images/{itemId}")
    fun clearItemCache(
        @PathVariable itemId: String,
    ): ResponseEntity<Any> {
        requireAdmin()?.let { return it }
        return ResponseEntity.ok(mapOf("cleared" to true, "itemId" to itemId, "entriesCleared" to 0))
    }

    @PostMapping("/Library/Rescan")
    fun rescan(): ResponseEntity<Any> {
        requireAdmin()?.let { return it }
        // TODO: wire to infra-library-scanner. The scanner is a worker module that adapter-jellyfin
        // cannot depend on directly (architecture rule: adapters do not depend on workers).
        // Proper fix: expose a port (e.g. LibraryScanTrigger) in core-application/library,
        // implemented by infra-library-scanner, and inject the port here.
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(mapOf("status" to "started", "note" to "scanner trigger port not yet defined"))
    }

    @GetMapping("/Library/ScanStatus")
    fun scanStatus(): ResponseEntity<Any> {
        requireAdmin()?.let { return it }
        // TODO: no ScanRecord query port exists yet; return inert stub for the PWA.
        return ResponseEntity.ok(mapOf("scanning" to false, "isScanning" to false, "progress" to 100))
    }

    companion object {
        // OWASP 2025/2026 Password Storage guidance: bcrypt work factor >= 12 (13 chosen for headroom).
        private const val BCRYPT_COST = 13
    }
}
