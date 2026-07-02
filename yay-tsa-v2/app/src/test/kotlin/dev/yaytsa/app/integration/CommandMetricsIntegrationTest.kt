package dev.yaytsa.app.integration

import dev.yaytsa.application.auth.AuthUseCases
import dev.yaytsa.domain.auth.CreateUser
import dev.yaytsa.domain.auth.UpdateProfile
import dev.yaytsa.shared.AggregateVersion
import dev.yaytsa.shared.CommandContext
import dev.yaytsa.shared.CommandResult
import dev.yaytsa.shared.IdempotencyKey
import dev.yaytsa.shared.ProtocolId
import dev.yaytsa.shared.UserId
import io.micrometer.core.instrument.MeterRegistry
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit

class CommandMetricsIntegrationTest : HttpIntegrationTestBase() {
    @Autowired
    lateinit var authUseCases: AuthUseCases

    @Autowired
    lateinit var meterRegistry: MeterRegistry

    private fun createUserCommand(username: String): UserId {
        val uid = UserId(UUID.randomUUID().toString())
        val passwordHash =
            org.springframework.security.crypto.bcrypt.BCrypt
                .hashpw(
                    "metrics-password",
                    org.springframework.security.crypto.bcrypt.BCrypt
                        .gensalt(),
                )
        val cmd = CreateUser(uid, username, passwordHash, null, null, false)
        val ctx = CommandContext(uid, ProtocolId("JELLYFIN"), Instant.now(), IdempotencyKey(UUID.randomUUID().toString()), AggregateVersion.INITIAL)
        val result = authUseCases.execute(cmd, ctx)
        assertTrue(result is CommandResult.Success, "User creation should succeed")
        return uid
    }

    @Test
    fun `successful command execution is timed with context command and outcome tags`() {
        createUserCommand("metrics-ok-${UUID.randomUUID().toString().take(8)}")

        val timer =
            meterRegistry
                .find("yaytsa.command.execution")
                .tags("context", "auth", "command", "CreateUser", "outcome", "success")
                .timer()
        assertNotNull(timer, "yaytsa.command.execution{auth,CreateUser,success} must be registered")
        assertTrue(timer!!.count() >= 1, "timer must have recorded at least one execution")
        assertTrue(timer.totalTime(TimeUnit.NANOSECONDS) > 0, "timer must have recorded a duration")
    }

    @Test
    fun `failed command execution is tagged with the failure type`() {
        val missingUser = UserId(UUID.randomUUID().toString())
        val cmd = UpdateProfile(missingUser, "Ghost", null)
        val ctx =
            CommandContext(missingUser, ProtocolId("JELLYFIN"), Instant.now(), IdempotencyKey(UUID.randomUUID().toString()), AggregateVersion.INITIAL)
        val result = authUseCases.execute(cmd, ctx)
        assertTrue(result is CommandResult.Failed, "command against a missing user must fail")

        val timer =
            meterRegistry
                .find("yaytsa.command.execution")
                .tags("context", "auth", "command", "UpdateProfile", "outcome", "NotFound")
                .timer()
        assertNotNull(timer, "yaytsa.command.execution{auth,UpdateProfile,NotFound} must be registered")
        assertTrue(timer!!.count() >= 1)
    }

    @Test
    fun `outbox delivery is counted and backlog gauges are registered`() {
        createUserCommand("metrics-outbox-${UUID.randomUUID().toString().take(8)}")

        await().atMost(15, TimeUnit.SECONDS).untilAsserted {
            val delivered =
                meterRegistry
                    .find("yaytsa.outbox.delivered")
                    .tags("context", "auth")
                    .counter()
            assertNotNull(delivered, "yaytsa.outbox.delivered{context=auth} must appear after the poller publishes")
            assertTrue(delivered!!.count() >= 1.0)
        }

        assertNotNull(meterRegistry.find("yaytsa.outbox.pending").gauge(), "yaytsa.outbox.pending gauge must be registered")
        assertNotNull(
            meterRegistry.find("yaytsa.outbox.oldest.age.seconds").gauge(),
            "yaytsa.outbox.oldest.age.seconds gauge must be registered",
        )
    }

    @Test
    fun `llm misconfiguration gauge is registered and healthy when llm is disabled`() {
        val gauge = meterRegistry.find("yaytsa.llm.misconfigured").gauge()
        assertNotNull(gauge, "yaytsa.llm.misconfigured gauge must be registered")
        assertEquals(0.0, gauge!!.value(), "llm disabled must not report misconfiguration")
    }

    @Test
    fun `http server request metrics are recorded`() {
        assertEquals(200, get("/System/Ping").response.status)
        assertNotNull(
            meterRegistry.find("http.server.requests").timer(),
            "http.server.requests must be recorded by actuator web metrics",
        )
    }
}
