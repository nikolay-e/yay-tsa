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
import org.springframework.jdbc.core.JdbcTemplate
import java.time.Instant
import java.util.UUID

class PlaybackReflectionIntegrationTest : HttpIntegrationTestBase() {
    @Autowired
    lateinit var authUseCases: AuthUseCases

    @Autowired
    lateinit var jdbc: JdbcTemplate

    private lateinit var token: String
    private lateinit var userId: String
    private lateinit var deviceId: String

    @BeforeEach
    fun seedUser() {
        userId = UUID.randomUUID().toString()
        token = UUID.randomUUID().toString()
        deviceId = "refl-${UUID.randomUUID().toString().take(8)}"
        val uid = UserId(userId)
        val now = Instant.now()
        authUseCases.execute(
            CreateUser(uid, "refl-${userId.take(8)}", "testpassword", "Test", null, false),
            CommandContext(uid, ProtocolId("JELLYFIN"), now, IdempotencyKey(UUID.randomUUID().toString()), AggregateVersion.INITIAL),
        )
        authUseCases.execute(
            CreateApiToken(uid, ApiTokenId(UUID.randomUUID().toString()), token, DeviceId(deviceId), "Test Device", null),
            CommandContext(uid, ProtocolId("JELLYFIN"), now, IdempotencyKey(UUID.randomUUID().toString()), AggregateVersion(1)),
        )
    }

    private fun registerDevice() {
        assertEquals(200, post("/v1/me/devices/heartbeat", emptyMap<String, Any>(), token).response.status)
    }

    @Test
    fun `playing report reflects device playback into Sessions now playing`() {
        val trackId = seedTrack()
        registerDevice()

        val report = post("/Sessions/Playing", mapOf("ItemId" to trackId, "PositionTicks" to 0L), token)
        assertEquals(204, report.response.status)

        val sessions = objectMapper.readTree(get("/Sessions", token).response.contentAsString)
        val mine = sessions.first { it.get("DeviceId").asText() == deviceId }
        assertEquals(trackId, mine.get("NowPlayingItem").get("Id").asText(), "reflected track must surface as NowPlayingItem")
        assertEquals(false, mine.get("PlayState").get("IsPaused").asBoolean(), "reported start must reflect as playing")
    }

    @Test
    fun `paused progress report reflects paused state and position`() {
        val trackId = seedTrack()
        registerDevice()
        post("/Sessions/Playing", mapOf("ItemId" to trackId, "PositionTicks" to 0L), token)

        val pausedTicks = 30_000L * 10_000
        val progress =
            post(
                "/Sessions/Playing/Progress",
                mapOf("ItemId" to trackId, "PositionTicks" to pausedTicks, "IsPaused" to true),
                token,
            )
        assertEquals(204, progress.response.status)

        val sessions = objectMapper.readTree(get("/Sessions", token).response.contentAsString)
        val mine = sessions.first { it.get("DeviceId").asText() == deviceId }
        assertTrue(mine.get("PlayState").get("IsPaused").asBoolean(), "paused report must reflect as paused")
        assertEquals(pausedTicks, mine.get("PlayState").get("PositionTicks").asLong(), "position must reflect the report")
    }

    @Test
    fun `stopped report reflects stopped state`() {
        val trackId = seedTrack()
        registerDevice()
        post("/Sessions/Playing", mapOf("ItemId" to trackId, "PositionTicks" to 0L), token)
        val stop = post("/Sessions/Playing/Stopped", mapOf("ItemId" to trackId, "PositionTicks" to 0L), token)
        assertEquals(204, stop.response.status)

        val sessions = objectMapper.readTree(get("/Sessions", token).response.contentAsString)
        val mine = sessions.first { it.get("DeviceId").asText() == deviceId }
        assertTrue(mine.get("PlayState").get("IsPaused").asBoolean(), "stopped session must not read as playing")
    }

    private fun seedTrack(): String {
        val id = UUID.randomUUID()
        jdbc.update(
            "INSERT INTO core_v2_library.entities (id, entity_type, name, sort_name, source_path, search_text) VALUES (?,?,?,?,?,?)",
            id,
            "TRACK",
            "ReflTrack-${id.toString().take(6)}",
            "refltrack",
            "/refltest/$id.flac",
            "refltrack",
        )
        jdbc.update("INSERT INTO core_v2_library.audio_tracks (entity_id, duration_ms) VALUES (?,?)", id, 100000L)
        return id.toString()
    }
}
