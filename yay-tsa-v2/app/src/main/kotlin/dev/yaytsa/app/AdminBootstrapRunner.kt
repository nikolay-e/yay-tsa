package dev.yaytsa.app

import dev.yaytsa.application.auth.AuthQueries
import dev.yaytsa.application.auth.AuthUseCases
import dev.yaytsa.application.shared.port.Clock
import dev.yaytsa.domain.auth.CreateUser
import dev.yaytsa.shared.AggregateVersion
import dev.yaytsa.shared.CommandContext
import dev.yaytsa.shared.CommandResult
import dev.yaytsa.shared.IdempotencyKey
import dev.yaytsa.shared.ProtocolId
import dev.yaytsa.shared.UserId
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.security.crypto.bcrypt.BCrypt
import org.springframework.stereotype.Component
import java.util.UUID

@Component
class AdminBootstrapRunner(
    private val authUseCases: AuthUseCases,
    private val authQueries: AuthQueries,
    private val clock: Clock,
    @Value("\${yaytsa.admin-bootstrap.enabled:true}")
    private val enabled: Boolean,
    @Value("\${yaytsa.admin-bootstrap.username:admin}")
    private val username: String,
    @Value("\${yaytsa.admin-bootstrap.password:#{null}}")
    private val password: String?,
    @Value("\${yaytsa.admin-bootstrap.display-name:Administrator}")
    private val displayName: String,
) {
    private val log = LoggerFactory.getLogger(AdminBootstrapRunner::class.java)

    @EventListener(ApplicationReadyEvent::class)
    fun bootstrapFirstAdmin() {
        if (!enabled) {
            log.info("Admin bootstrap disabled; skipping first-admin seed")
            return
        }

        if (password.isNullOrBlank()) {
            log.warn(
                "Admin bootstrap enabled but ADMIN_BOOTSTRAP_PASSWORD is unset; " +
                    "no admin created (fail-closed). Set ADMIN_BOOTSTRAP_PASSWORD to seed the first admin.",
            )
            return
        }

        if (authQueries.listAll().any { it.isAdmin }) {
            log.info("Admin already exists; skipping first-admin seed")
            return
        }

        val userId = UserId(UUID.randomUUID().toString())
        val passwordHash = BCrypt.hashpw(password, BCrypt.gensalt(BCRYPT_COST))
        val cmd =
            CreateUser(
                userId = userId,
                username = username,
                passwordHash = passwordHash,
                displayName = displayName,
                email = null,
                isAdmin = true,
            )
        val ctx =
            CommandContext(
                userId = userId,
                protocolId = ProtocolId("BOOTSTRAP"),
                requestTime = clock.now(),
                idempotencyKey = IdempotencyKey(UUID.randomUUID().toString()),
                expectedVersion = AggregateVersion.INITIAL,
            )

        when (val result = authUseCases.execute(cmd, ctx)) {
            is CommandResult.Success -> log.info("Seeded first admin user '{}'", username)
            is CommandResult.Failed -> log.error("Failed to seed first admin user: {}", result.failure)
        }
    }

    companion object {
        private const val BCRYPT_COST = 13
    }
}
