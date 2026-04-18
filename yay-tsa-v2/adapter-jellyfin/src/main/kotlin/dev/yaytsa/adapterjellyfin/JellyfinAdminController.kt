package dev.yaytsa.adapterjellyfin

import dev.yaytsa.application.auth.AuthQueries
import dev.yaytsa.application.auth.AuthUseCases
import dev.yaytsa.domain.auth.CreateUser
import dev.yaytsa.shared.AggregateVersion
import dev.yaytsa.shared.CommandContext
import dev.yaytsa.shared.CommandResult
import dev.yaytsa.shared.IdempotencyKey
import dev.yaytsa.shared.ProtocolId
import dev.yaytsa.shared.UserId
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
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
    private val clock: dev.yaytsa.application.shared.port.Clock,
) {
    @GetMapping("/Users")
    fun listUsers(): ResponseEntity<Any> {
        val users = authQueries.listAll()
        return ResponseEntity.ok(
            users.map { u ->
                mapOf(
                    "Id" to u.id.value,
                    "Name" to u.username,
                    "IsAdmin" to u.isAdmin,
                    "IsActive" to u.isActive,
                )
            },
        )
    }

    data class CreateUserRequest(
        val username: String,
        val displayName: String? = null,
        val isAdmin: Boolean = false,
    )

    @PostMapping("/Users")
    fun createUser(
        @RequestBody request: CreateUserRequest,
    ): ResponseEntity<Any> {
        val uid = UserId(UUID.randomUUID().toString())
        val passwordHash = org.springframework.security.crypto.bcrypt.BCrypt.hashpw("changeme", org.springframework.security.crypto.bcrypt.BCrypt.gensalt())
        val cmd = CreateUser(uid, request.username, passwordHash, request.displayName, null, request.isAdmin)
        val ctx = CommandContext(uid, ProtocolId("JELLYFIN"), clock.now(), IdempotencyKey(UUID.randomUUID().toString()), AggregateVersion.INITIAL)
        return when (val result = authUseCases.execute(cmd, ctx)) {
            is CommandResult.Success -> ResponseEntity.ok(mapOf("user" to mapOf("Id" to uid.value, "Name" to request.username), "initialPassword" to "changeme"))
            is CommandResult.Failed -> ResponseEntity.status(HttpStatus.BAD_REQUEST).body(mapOf("error" to result.failure.toString()))
        }
    }

    @DeleteMapping("/Users/{userId}")
    fun deleteUser(
        @PathVariable userId: String,
    ): ResponseEntity<Void> {
        val uid = UserId(userId)
        val user = authQueries.findUser(uid) ?: return ResponseEntity.notFound().build()
        val cmd = dev.yaytsa.domain.auth.DeactivateUser(uid)
        val ctx = CommandContext(uid, ProtocolId("JELLYFIN"), clock.now(), IdempotencyKey(UUID.randomUUID().toString()), user.version)
        authUseCases.execute(cmd, ctx)
        return ResponseEntity.noContent().build()
    }

    @PostMapping("/Users/{userId}/ResetPassword")
    fun resetPassword(
        @PathVariable userId: String,
    ): ResponseEntity<Any> {
        val uid = UserId(userId)
        val user = authQueries.findUser(uid) ?: return ResponseEntity.notFound().build()
        val newPassword = UUID.randomUUID().toString().take(12)
        val newHash = org.springframework.security.crypto.bcrypt.BCrypt.hashpw(newPassword, org.springframework.security.crypto.bcrypt.BCrypt.gensalt())
        val cmd = dev.yaytsa.domain.auth.ChangePassword(uid, newHash)
        val ctx = CommandContext(uid, ProtocolId("JELLYFIN"), clock.now(), IdempotencyKey(UUID.randomUUID().toString()), user.version)
        authUseCases.execute(cmd, ctx)
        return ResponseEntity.ok(mapOf("newPassword" to newPassword))
    }

    @GetMapping("/Cache/Stats")
    fun cacheStats(): ResponseEntity<Any> = ResponseEntity.ok(mapOf("imageCache" to mapOf("size" to 0, "hitCount" to 0, "missCount" to 0, "hitRate" to 0.0)))

    @DeleteMapping("/Cache")
    fun clearCache(): ResponseEntity<Any> = ResponseEntity.ok(mapOf("cleared" to true, "entriesCleared" to 0))

    @DeleteMapping("/Cache/Images/{itemId}")
    fun clearItemCache(
        @PathVariable itemId: String,
    ): ResponseEntity<Any> = ResponseEntity.ok(mapOf("cleared" to true, "itemId" to itemId, "entriesCleared" to 0))

    @PostMapping("/Library/Rescan")
    fun rescan(): ResponseEntity<Any> = ResponseEntity.ok(mapOf("status" to "started"))

    @GetMapping("/Library/ScanStatus")
    fun scanStatus(): ResponseEntity<Any> = ResponseEntity.ok(mapOf("isScanning" to false, "progress" to 100))
}
