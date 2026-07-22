package dev.yaytsa.app.integration

import dev.yaytsa.application.auth.AuthUseCases
import dev.yaytsa.application.playback.port.PlayHistoryWritePort
import dev.yaytsa.domain.auth.ApiTokenId
import dev.yaytsa.domain.auth.CreateApiToken
import dev.yaytsa.domain.auth.CreateUser
import dev.yaytsa.shared.AggregateVersion
import dev.yaytsa.shared.CommandContext
import dev.yaytsa.shared.DeviceId
import dev.yaytsa.shared.IdempotencyKey
import dev.yaytsa.shared.ProtocolId
import dev.yaytsa.shared.TrackId
import dev.yaytsa.shared.UserId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders
import java.time.Instant
import java.util.UUID

class PlayHistoryDedupIntegrationTest : HttpIntegrationTestBase() {
    @Autowired
    lateinit var authUseCases: AuthUseCases

    @Autowired
    lateinit var playHistoryWriter: PlayHistoryWritePort

    @Autowired
    lateinit var jdbc: JdbcTemplate

    private lateinit var userId: String
    private lateinit var token: String

    @BeforeEach
    fun seedUser() {
        userId = UUID.randomUUID().toString()
        token = UUID.randomUUID().toString()
        val uid = UserId(userId)
        val now = Instant.now()

        val createCmd = CreateUser(uid, "dedup-${UUID.randomUUID().toString().take(8)}", "testpassword", "Test", null, false)
        val ctx = CommandContext(uid, ProtocolId("JELLYFIN"), now, IdempotencyKey(UUID.randomUUID().toString()), AggregateVersion.INITIAL)
        authUseCases.execute(createCmd, ctx)

        val tokenCmd = CreateApiToken(uid, ApiTokenId(UUID.randomUUID().toString()), token, DeviceId("test"), "Test", null)
        val tokenCtx = CommandContext(uid, ProtocolId("JELLYFIN"), now, IdempotencyKey(UUID.randomUUID().toString()), AggregateVersion(1))
        authUseCases.execute(tokenCmd, tokenCtx)
    }

    private fun playHistoryCount(itemId: String): Int =
        jdbc.queryForObject(
            "SELECT count(*) FROM core_v2_playback.play_history WHERE user_id = ? AND item_id = ?",
            Int::class.java,
            userId,
            itemId,
        ) ?: 0

    @Test
    fun `two identical Stopped beacons write one play_history row`() {
        val itemId = UUID.randomUUID().toString()
        val body = """{"ItemId":"$itemId","PositionTicks":2400000000}"""

        repeat(2) {
            val result =
                mockMvc
                    .perform(
                        MockMvcRequestBuilders
                            .post("/Sessions/Playing/Stopped")
                            .header("X-Emby-Token", token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body),
                    ).andReturn()
            assertEquals(204, result.response.status)
        }

        assertEquals(1, playHistoryCount(itemId), "a retried Stopped beacon must not double-count the play")
    }

    @Test
    fun `genuine re-listen with a started_at outside the dedup window writes a second play_history row`() {
        val itemId = UUID.randomUUID().toString()
        val uid = UserId(userId)
        val trackId = TrackId(itemId)
        val firstStart = Instant.now()

        playHistoryWriter.record(uid, trackId, firstStart, 240_000, 240_000, completed = true, skipped = false)
        assertEquals(1, playHistoryCount(itemId))

        val outsideWindow = firstStart.plusSeconds(31 * 60)
        playHistoryWriter.record(uid, trackId, outsideWindow, 240_000, 240_000, completed = true, skipped = false)

        assertEquals(2, playHistoryCount(itemId), "a genuine re-listen outside the started_at window must be counted separately")
    }

    @Test
    fun `phantom re-scrobble with a shifted started_at inside the window is suppressed`() {
        val itemId = UUID.randomUUID().toString()
        val uid = UserId(userId)
        val trackId = TrackId(itemId)
        val realStart = Instant.now()

        playHistoryWriter.record(uid, trackId, realStart, 450_000, 450_000, completed = true, skipped = false)
        assertEquals(1, playHistoryCount(itemId))

        val backdatedStart = realStart.plusSeconds(20 * 60)
        playHistoryWriter.record(uid, trackId, backdatedStart, 450_000, 450_000, completed = true, skipped = false)

        assertEquals(1, playHistoryCount(itemId), "a phantom duplicate of the same play must not inflate the replay signal")
    }

    private fun recordedSource(itemId: String): String? =
        jdbc.queryForObject(
            "SELECT source FROM core_v2_playback.play_history WHERE user_id = ? AND item_id = ?",
            String::class.java,
            userId,
            itemId,
        )

    private fun postStopped(body: String) {
        val result =
            mockMvc
                .perform(
                    MockMvcRequestBuilders
                        .post("/Sessions/Playing/Stopped")
                        .header("X-Emby-Token", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body),
                ).andReturn()
        assertEquals(204, result.response.status)
    }

    @Test
    fun `client-declared PlaySource is recorded as the play_history source`() {
        val itemId = UUID.randomUUID().toString()
        postStopped("""{"ItemId":"$itemId","PositionTicks":2400000000,"PlaySource":"Album"}""")

        assertEquals("album", recordedSource(itemId), "PlaySource must survive (normalized) into play_history.source")
    }

    @Test
    fun `absent PlaySource falls back to the protocol source, never null`() {
        val itemId = UUID.randomUUID().toString()
        postStopped("""{"ItemId":"$itemId","PositionTicks":2400000000}""")

        assertEquals("jellyfin", recordedSource(itemId), "an unattributed Jellyfin scrobble must carry the protocol source")
    }

    @Test
    fun `garbage PlaySource is dropped in favor of the protocol source`() {
        val itemId = UUID.randomUUID().toString()
        postStopped("""{"ItemId":"$itemId","PositionTicks":2400000000,"PlaySource":"way-too-long-to-be-a-real-surface; DROP"}""")

        assertEquals("jellyfin", recordedSource(itemId), "junk outside the slug alphabet must not reach the analytics column")
    }
}
