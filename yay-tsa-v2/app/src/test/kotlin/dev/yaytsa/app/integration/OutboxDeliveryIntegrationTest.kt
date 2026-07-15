package dev.yaytsa.app.integration

import dev.yaytsa.application.auth.AuthUseCases
import dev.yaytsa.domain.auth.ApiTokenId
import dev.yaytsa.domain.auth.CreateApiToken
import dev.yaytsa.domain.auth.CreateUser
import dev.yaytsa.persistence.shared.OutboxCleanupJob
import dev.yaytsa.shared.AggregateVersion
import dev.yaytsa.shared.CommandContext
import dev.yaytsa.shared.DeviceId
import dev.yaytsa.shared.IdempotencyKey
import dev.yaytsa.shared.ProtocolId
import dev.yaytsa.shared.UserId
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit

class OutboxDeliveryIntegrationTest : HttpIntegrationTestBase() {
    @Autowired
    lateinit var authUseCases: AuthUseCases

    @Autowired
    lateinit var jdbc: JdbcTemplate

    @Autowired
    lateinit var outboxCleanupJob: OutboxCleanupJob

    private lateinit var token: String
    private lateinit var userId: String
    private lateinit var deviceId: String

    @BeforeEach
    fun seedUser() {
        userId = UUID.randomUUID().toString()
        token = UUID.randomUUID().toString()
        deviceId = "outbox-${UUID.randomUUID().toString().take(8)}"
        val uid = UserId(userId)
        val now = Instant.now()
        authUseCases.execute(
            CreateUser(uid, "outbox-${userId.take(8)}", "testpassword", "Test", null, false),
            CommandContext(uid, ProtocolId("JELLYFIN"), now, IdempotencyKey(UUID.randomUUID().toString()), AggregateVersion.INITIAL),
        )
        authUseCases.execute(
            CreateApiToken(uid, ApiTokenId(UUID.randomUUID().toString()), token, DeviceId(deviceId), "Test Device", null),
            CommandContext(uid, ProtocolId("JELLYFIN"), now, IdempotencyKey(UUID.randomUUID().toString()), AggregateVersion(1)),
        )
    }

    @Test
    fun `http command enqueues outbox row and the poller delivers it to the sse consumer`() {
        mintReflectedSession(seedTrack())
        val sse = subscribeSse()

        val response = post("/v1/me/devices/$deviceId/command", mapOf("command" to "pause"), token)
        assertEquals(200, response.response.status, response.response.contentAsString)

        val content = awaitSse(sse) { it.contains("\"type\":\"PAUSE\"") && it.contains("\"targetDeviceId\":\"$deviceId\"") }
        val commandId = extractCommandId(content)

        await().atMost(15, TimeUnit.SECONDS).untilAsserted {
            assertNotNull(publishedAt(commandId), "outbox row for $commandId must be marked published after delivery")
        }
        assertEquals("device-command", contextOf(commandId))
    }

    @Test
    fun `delivered notification is not redelivered on subsequent poller ticks`() {
        mintReflectedSession(seedTrack())
        val sse = subscribeSse()

        assertEquals(200, post("/v1/me/devices/$deviceId/command", mapOf("command" to "pause"), token).response.status)
        val firstCommandId = extractCommandId(awaitSse(sse) { it.contains("\"type\":\"PAUSE\"") })
        await().atMost(15, TimeUnit.SECONDS).until { publishedAt(firstCommandId) != null }
        val publishedAtFirstDelivery = publishedAt(firstCommandId)

        assertEquals(200, post("/v1/me/devices/$deviceId/command", mapOf("command" to "skip_next"), token).response.status)
        val content = awaitSse(sse) { it.contains("\"type\":\"NEXT\"") }

        val firstDeliveries = Regex(Regex.escape(firstCommandId)).findAll(content).count()
        assertEquals(1, firstDeliveries, "first command must be delivered exactly once, stream was: $content")
        assertEquals(publishedAtFirstDelivery, publishedAt(firstCommandId), "published_at must not move once delivered")
    }

    @Test
    fun `cleanup deletes only published rows older than retention and never undelivered ones`() {
        val stalePublished = insertOutboxRow(createdAt = hoursAgo(26), publishedAt = hoursAgo(25))
        val freshPublished = insertOutboxRow(createdAt = hoursAgo(2), publishedAt = hoursAgo(1))
        val oldUnpublished = insertOutboxRow(createdAt = hoursAgo(26), publishedAt = null)

        outboxCleanupJob.cleanup()

        assertEquals(0, countRows(stalePublished), "published row past retention must be cleaned up")
        assertEquals(1, countRows(freshPublished), "recently published row must survive cleanup")
        assertEquals(1, countRows(oldUnpublished), "undelivered row must never be cleaned up regardless of age")
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

    private fun awaitSse(
        sse: MvcResult,
        predicate: (String) -> Boolean,
    ): String {
        await().atMost(15, TimeUnit.SECONDS).until { predicate(sse.response.contentAsString) }
        return sse.response.contentAsString
    }

    private fun extractCommandId(sseContent: String): String {
        val match = Regex("\"commandId\":\"([0-9a-f-]{36})\"").find(sseContent)
        assertNotNull(match, "sse stream must carry a commandId, was: $sseContent")
        return match!!.groupValues[1]
    }

    private fun publishedAt(commandId: String): Timestamp? =
        jdbc
            .query(
                "SELECT published_at FROM core_v2_shared.outbox WHERE payload LIKE ?",
                { rs, _ -> rs.getTimestamp("published_at") to true },
                "%$commandId%",
            ).singleOrNull()
            ?.first

    private fun contextOf(commandId: String): String? =
        jdbc
            .query(
                "SELECT context FROM core_v2_shared.outbox WHERE payload LIKE ?",
                { rs, _ -> rs.getString("context") },
                "%$commandId%",
            ).singleOrNull()

    private fun insertOutboxRow(
        createdAt: Timestamp,
        publishedAt: Timestamp?,
    ): UUID {
        val id = UUID.randomUUID()
        jdbc.update(
            "INSERT INTO core_v2_shared.outbox (id, context, payload, created_at, published_at) VALUES (?,?,?,?,?)",
            id,
            "cleanup-probe",
            """{"marker":"$id"}""",
            createdAt,
            publishedAt,
        )
        return id
    }

    private fun countRows(id: UUID): Int = jdbc.queryForObject("SELECT COUNT(*) FROM core_v2_shared.outbox WHERE id = ?", Int::class.java, id)!!

    private fun hoursAgo(hours: Long): Timestamp = Timestamp.from(Instant.now().minusSeconds(hours * 3600))

    private fun seedTrack(): String {
        val id = UUID.randomUUID()
        jdbc.update(
            "INSERT INTO core_v2_library.entities (id, entity_type, name, sort_name, source_path, search_text) VALUES (?,?,?,?,?,?)",
            id,
            "TRACK",
            "OutboxTrack-${id.toString().take(6)}",
            "outboxtrack",
            "/outboxtest/$id.flac",
            "outboxtrack",
        )
        jdbc.update("INSERT INTO core_v2_library.audio_tracks (entity_id, duration_ms) VALUES (?,?)", id, 100000L)
        return id.toString()
    }
}
