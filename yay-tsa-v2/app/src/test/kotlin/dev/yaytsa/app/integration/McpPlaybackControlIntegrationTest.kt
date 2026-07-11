package dev.yaytsa.app.integration

import com.fasterxml.jackson.databind.JsonNode
import dev.yaytsa.application.auth.AuthUseCases
import dev.yaytsa.application.playback.DeviceSessionProjection
import dev.yaytsa.domain.auth.ApiTokenId
import dev.yaytsa.domain.auth.CreateApiToken
import dev.yaytsa.domain.auth.CreateUser
import dev.yaytsa.domain.playback.SessionId
import dev.yaytsa.shared.AggregateVersion
import dev.yaytsa.shared.CommandContext
import dev.yaytsa.shared.DeviceId
import dev.yaytsa.shared.IdempotencyKey
import dev.yaytsa.shared.ProtocolId
import dev.yaytsa.shared.UserId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders
import java.time.Instant
import java.util.UUID

class McpPlaybackControlIntegrationTest : HttpIntegrationTestBase() {
    @Autowired
    lateinit var authUseCases: AuthUseCases

    @Autowired
    lateinit var jdbc: JdbcTemplate

    @Autowired
    lateinit var deviceSessionProjection: DeviceSessionProjection

    private lateinit var token: String
    private lateinit var userId: String
    private lateinit var deviceId: String

    @BeforeEach
    fun seedUser() {
        userId = UUID.randomUUID().toString()
        token = UUID.randomUUID().toString()
        deviceId = "mcpctl-${UUID.randomUUID().toString().take(8)}"
        val uid = UserId(userId)
        val now = Instant.now()
        authUseCases.execute(
            CreateUser(uid, "mcpctl-${userId.take(8)}", "testpassword", "Test", null, false),
            CommandContext(uid, ProtocolId("JELLYFIN"), now, IdempotencyKey(UUID.randomUUID().toString()), AggregateVersion.INITIAL),
        )
        authUseCases.execute(
            CreateApiToken(uid, ApiTokenId(UUID.randomUUID().toString()), token, DeviceId(deviceId), "Test Device", null),
            CommandContext(uid, ProtocolId("JELLYFIN"), now, IdempotencyKey(UUID.randomUUID().toString()), AggregateVersion(1)),
        )
    }

    @Test
    fun `mcp pause publishes pause command to the lease owner over sse`() {
        val trackId = seedTrack()
        mintReflectedSession(trackId)
        val sse = subscribeSse()

        val result = mcpToolCall("pause")
        assertFalse(result.get("isError").asBoolean(), result.toString())
        val text = toolText(result)
        assertTrue(text.contains("sent") || text.contains("confirmed"), text)

        val content = awaitSseContent(sse) { it.contains("\"type\":\"PAUSE\"") }
        assertTrue(content.contains("\"type\":\"PAUSE\""), content)
        assertTrue(content.contains("\"targetDeviceId\":\"$deviceId\""), content)
        assertTrue(content.contains("\"commandId\":"), content)
    }

    @Test
    fun `mcp pause without an online device is an error`() {
        val trackId = seedTrack()
        mintReflectedSession(trackId)
        deviceSessionProjection.register(
            UserId(userId),
            SessionId(deviceId),
            DeviceId(deviceId),
            Instant.now().minusSeconds(300),
            "Test Device",
        )

        val result = mcpToolCall("pause")
        assertTrue(result.get("isError").asBoolean(), result.toString())
        assertTrue(toolText(result).contains("No reachable player device"), toolText(result))
    }

    @Test
    fun `mcp skip_next on a single entry reflected queue still publishes next`() {
        val trackId = seedTrack()
        mintReflectedSession(trackId)
        val sse = subscribeSse()

        val result = mcpToolCall("skip_next")
        assertFalse(result.get("isError").asBoolean(), result.toString())

        val content = awaitSseContent(sse) { it.contains("\"type\":\"NEXT\"") }
        assertTrue(content.contains("\"type\":\"NEXT\""), content)
        assertTrue(content.contains("\"targetDeviceId\":\"$deviceId\""), content)
    }

    @Test
    fun `mcp clear_queue publishes clear queue command`() {
        val trackId = seedTrack()
        mintReflectedSession(trackId)
        val sse = subscribeSse()

        val result = mcpToolCall("clear_queue")
        assertFalse(result.get("isError").asBoolean(), result.toString())
        assertTrue(toolText(result).contains("sent"), toolText(result))

        val content = awaitSseContent(sse) { it.contains("\"type\":\"CLEAR_QUEUE\"") }
        assertTrue(content.contains("\"type\":\"CLEAR_QUEUE\""), content)
    }

    @Test
    fun `mcp add_to_queue publishes enqueue with track ids payload`() {
        val trackId = seedTrack()
        val extraTrackId = seedTrack()
        mintReflectedSession(trackId)
        val sse = subscribeSse()

        val result = mcpToolCall("add_to_queue", mapOf("track_ids" to listOf(extraTrackId)))
        assertFalse(result.get("isError").asBoolean(), result.toString())

        val content = awaitSseContent(sse) { it.contains("\"type\":\"ENQUEUE\"") }
        assertTrue(content.contains("\"type\":\"ENQUEUE\""), content)
        assertTrue(content.contains("\"trackIds\":[\"$extraTrackId\"]"), content)
    }

    @Test
    fun `mcp add_to_queue with many track ids succeeds (outbox payload not truncated at 255)`() {
        // Regression for the varchar(255) outbox.payload bug: a batch enqueue serializes to a JSON
        // payload well over 255 chars, which failed the insert with SQLState 22001 and made every
        // multi-track enqueue error out while a single-track one worked.
        val seedForSession = seedTrack()
        val batch = (1..12).map { seedTrack() }
        mintReflectedSession(seedForSession)
        val sse = subscribeSse()

        val result = mcpToolCall("add_to_queue", mapOf("track_ids" to batch))
        assertFalse(result.get("isError").asBoolean(), result.toString())

        val content = awaitSseContent(sse) { it.contains("\"type\":\"ENQUEUE\"") }
        batch.forEach { id -> assertTrue(content.contains(id), "enqueued id $id missing from published command: $content") }
    }

    @Test
    fun `mcp play_track replaces the queue with the one track and starts it`() {
        val current = seedTrack()
        val target = seedTrack()
        mintReflectedSession(current)
        val sse = subscribeSse()

        val result = mcpToolCall("play_track", mapOf("track_id" to target))
        assertFalse(result.get("isError").asBoolean(), result.toString())
        assertTrue(toolText(result).contains("Playing"), toolText(result))

        val content = awaitSseContent(sse) { it.contains("\"type\":\"SET_QUEUE\"") }
        assertTrue(content.contains("\"type\":\"SET_QUEUE\""), content)
        assertTrue(content.contains("\"trackIds\":[\"$target\"]"), content)
        assertTrue(content.contains("\"targetDeviceId\":\"$deviceId\""), content)
    }

    @Test
    fun `mcp play_track with an unknown track id is a clean error`() {
        val current = seedTrack()
        mintReflectedSession(current)
        val unknownId = UUID.randomUUID().toString()

        val result = mcpToolCall("play_track", mapOf("track_id" to unknownId))
        assertTrue(result.get("isError").asBoolean(), result.toString())
        assertTrue(toolText(result).contains("not found", ignoreCase = true), toolText(result))
    }

    @Test
    fun `mcp add_to_queue with an unknown track id lists it as invalid`() {
        val trackId = seedTrack()
        mintReflectedSession(trackId)
        val unknownId = UUID.randomUUID().toString()

        val result = mcpToolCall("add_to_queue", mapOf("track_ids" to listOf(unknownId)))
        assertTrue(result.get("isError").asBoolean(), result.toString())
        assertTrue(toolText(result).contains(unknownId), toolText(result))
    }

    @Test
    fun `get_preference_contract round trips set_preference_contract`() {
        val set =
            mcpToolCall(
                "set_preference_contract",
                mapOf("hard_rules" to "never play screamo", "dj_style" to "smooth genre transitions"),
            )
        assertFalse(set.get("isError").asBoolean(), set.toString())

        val read = mcpToolCall("get_preference_contract")
        assertFalse(read.get("isError").asBoolean(), read.toString())
        val text = toolText(read)
        assertTrue(text.contains("never play screamo"), text)
        assertTrue(text.contains("smooth genre transitions"), text)
    }

    @Test
    fun `stale unpaused report cannot resurrect playing after a newer pause`() {
        val trackId = seedTrack()
        mintReflectedSession(trackId)
        val base = System.currentTimeMillis()

        val pause =
            post(
                "/Sessions/Playing/Progress",
                mapOf("ItemId" to trackId, "PositionTicks" to 100_000_000L, "IsPaused" to true, "EventTime" to base - 5_000),
                token,
            )
        assertEquals(204, pause.response.status)

        val staleResume =
            post(
                "/Sessions/Playing/Progress",
                mapOf("ItemId" to trackId, "PositionTicks" to 50_000_000L, "IsPaused" to false, "EventTime" to base - 15_000),
                token,
            )
        assertEquals(204, staleResume.response.status)

        val sessions = objectMapper.readTree(get("/Sessions", token).response.contentAsString)
        val mine = sessions.first { it.get("DeviceId").asText() == deviceId }
        assertTrue(mine.get("PlayState").get("IsPaused").asBoolean(), "stale resume report must not resurrect PLAYING")
    }

    @Test
    fun `device command endpoint publishes remote command and returns 2xx`() {
        val trackId = seedTrack()
        mintReflectedSession(trackId)
        val sse = subscribeSse()

        val response = post("/v1/me/devices/$deviceId/command", mapOf("command" to "pause"), token)
        assertEquals(200, response.response.status, response.response.contentAsString)

        val content = awaitSseContent(sse) { it.contains("\"type\":\"PAUSE\"") }
        assertTrue(content.contains("\"type\":\"PAUSE\""), content)
        assertTrue(content.contains("\"targetDeviceId\":\"$deviceId\""), content)
    }

    private fun mintReflectedSession(trackId: String) {
        assertEquals(200, post("/v1/me/devices/heartbeat", emptyMap<String, Any>(), token).response.status)
        val playing =
            post(
                "/Sessions/Playing",
                mapOf("ItemId" to trackId, "PositionTicks" to 0L, "EventTime" to System.currentTimeMillis() - 20_000),
                token,
            )
        assertEquals(204, playing.response.status)
    }

    private fun subscribeSse(): MvcResult =
        mockMvc
            .perform(
                MockMvcRequestBuilders
                    .get("/v1/me/devices/events")
                    .header("Authorization", "Bearer $token"),
            ).andReturn()

    private fun awaitSseContent(
        sse: MvcResult,
        timeoutMs: Long = 10_000,
        predicate: (String) -> Boolean,
    ): String {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val content = sse.response.contentAsString
            if (predicate(content)) return content
            Thread.sleep(200)
        }
        return sse.response.contentAsString
    }

    private fun mcpToolCall(
        tool: String,
        args: Map<String, Any?> = emptyMap(),
    ): JsonNode {
        val body =
            mapOf(
                "jsonrpc" to "2.0",
                "id" to 1,
                "method" to "tools/call",
                "params" to mapOf("name" to tool, "arguments" to args),
            )
        val result = post("/mcp", body, token)
        assertEquals(200, result.response.status, result.response.contentAsString)
        return objectMapper.readTree(result.response.contentAsString).get("result")
    }

    private fun toolText(result: JsonNode): String =
        result
            .get("content")
            .get(0)
            .get("text")
            .asText()

    private fun seedTrack(): String {
        val id = UUID.randomUUID()
        jdbc.update(
            "INSERT INTO core_v2_library.entities (id, entity_type, name, sort_name, source_path, search_text) VALUES (?,?,?,?,?,?)",
            id,
            "TRACK",
            "McpCtlTrack-${id.toString().take(6)}",
            "mcpctltrack",
            "/mcpctltest/$id.flac",
            "mcpctltrack",
        )
        jdbc.update("INSERT INTO core_v2_library.audio_tracks (entity_id, duration_ms) VALUES (?,?)", id, 100000L)
        return id.toString()
    }
}
