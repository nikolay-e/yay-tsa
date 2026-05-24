package dev.yaytsa.app.integration

import dev.yaytsa.application.auth.AuthUseCases
import dev.yaytsa.domain.auth.ApiTokenId
import dev.yaytsa.domain.auth.CreateApiToken
import dev.yaytsa.domain.auth.CreateUser
import dev.yaytsa.shared.AggregateVersion
import dev.yaytsa.shared.CommandContext
import dev.yaytsa.shared.DeviceId
import dev.yaytsa.shared.IdempotencyKey
import dev.yaytsa.shared.ProtocolId
import dev.yaytsa.shared.UserId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.time.Instant
import java.util.UUID

class GroupSyncIntegrationTest : HttpIntegrationTestBase() {
    @Autowired
    lateinit var authUseCases: AuthUseCases

    private lateinit var token: String

    @BeforeEach
    fun seedUser() {
        val userId = UUID.randomUUID().toString()
        token = UUID.randomUUID().toString()
        val uid = UserId(userId)
        val now = Instant.now()
        authUseCases.execute(
            CreateUser(uid, "grp-${userId.take(8)}", "testpassword", "Test", null, false),
            CommandContext(uid, ProtocolId("JELLYFIN"), now, IdempotencyKey(UUID.randomUUID().toString()), AggregateVersion.INITIAL),
        )
        authUseCases.execute(
            CreateApiToken(uid, ApiTokenId(UUID.randomUUID().toString()), token, DeviceId("test-device"), "Test", null),
            CommandContext(uid, ProtocolId("JELLYFIN"), now, IdempotencyKey(UUID.randomUUID().toString()), AggregateVersion(1)),
        )
    }

    @Test
    fun `group lifecycle - create, snapshot, schedule with OCC, conflict, heartbeat, end`() {
        val trackId = UUID.randomUUID().toString()
        val create = post("/v1/groups", mapOf("name" to "Party", "trackId" to trackId), token)
        assertEquals(200, create.response.status)
        val created = objectMapper.readTree(create.response.contentAsString)
        val groupId = created.get("id").asText()
        assertTrue(created.get("joinCode").asText().isNotBlank())

        val snap = objectMapper.readTree(get("/v1/groups/$groupId", token).response.contentAsString)
        assertEquals(0, snap.get("schedule").get("scheduleEpoch").asInt())
        assertTrue(snap.get("members").any { it.get("deviceId").asText() == "test-device" }, "owner device must be a member")

        val sched = post("/v1/groups/$groupId/schedule", mapOf("expected_epoch" to 0, "action" to "PLAY", "positionMs" to 5000, "paused" to false), token)
        assertEquals(200, sched.response.status)
        val schedBody = objectMapper.readTree(sched.response.contentAsString)
        assertEquals(1, schedBody.get("scheduleEpoch").asInt(), "epoch must advance")
        assertTrue(schedBody.get("serverTimeMs").asLong() > 0)
        assertEquals(false, schedBody.get("schedule").get("isPaused").asBoolean())

        // Stale epoch must be rejected with 409.
        val conflict = post("/v1/groups/$groupId/schedule", mapOf("expected_epoch" to 0, "action" to "PAUSE"), token)
        assertEquals(409, conflict.response.status, "stale schedule_epoch must 409, not silently overwrite")

        assertTrue(post("/v1/groups/$groupId/heartbeat", mapOf("rttMs" to 20), token).response.status == 200)

        assertEquals(200, delete("/v1/groups/$groupId", token).response.status)
        assertEquals(404, get("/v1/groups/$groupId", token).response.status, "ended group must be gone")
    }

    @Test
    fun `time endpoint returns an epoch millis number without auth`() {
        val result = get("/v1/time")
        assertEquals(200, result.response.status)
        assertTrue(
            result.response.contentAsString
                .trim()
                .toLong() > 0,
            "v1/time must be a bare epoch-ms number",
        )
    }
}
