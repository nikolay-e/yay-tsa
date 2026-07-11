package dev.yaytsa.app.integration

import dev.yaytsa.application.auth.AuthUseCases
import dev.yaytsa.application.playback.ScrobbleService
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
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

class ScrobbleIntegrationTest : HttpIntegrationTestBase() {
    @Autowired
    lateinit var authUseCases: AuthUseCases

    @Autowired
    lateinit var scrobbleService: ScrobbleService

    @Autowired
    lateinit var jdbc: JdbcTemplate

    private lateinit var userId: String
    private lateinit var token: String
    private lateinit var deviceId: String

    @BeforeEach
    fun seedUser() {
        userId = UUID.randomUUID().toString()
        token = UUID.randomUUID().toString()
        deviceId = "scrob-${UUID.randomUUID().toString().take(8)}"
        val uid = UserId(userId)
        val now = Instant.now()
        authUseCases.execute(
            CreateUser(uid, "scrob-${userId.take(8)}", "testpassword", "Test", null, false),
            CommandContext(uid, ProtocolId("JELLYFIN"), now, IdempotencyKey(UUID.randomUUID().toString()), AggregateVersion.INITIAL),
        )
        authUseCases.execute(
            CreateApiToken(uid, ApiTokenId(UUID.randomUUID().toString()), token, DeviceId(deviceId), "Test Device", null),
            CommandContext(uid, ProtocolId("JELLYFIN"), now, IdempotencyKey(UUID.randomUUID().toString()), AggregateVersion(1)),
        )
    }

    private fun seedTrack(durationMs: Long = 100_000L): String {
        val id = UUID.randomUUID()
        jdbc.update(
            "INSERT INTO core_v2_library.entities (id, entity_type, name, sort_name, source_path, search_text) VALUES (?,?,?,?,?,?)",
            id,
            "TRACK",
            "ScrobTrack-${id.toString().take(6)}",
            "scrobtrack",
            "/scrobtest/$id.flac",
            "scrobtrack",
        )
        jdbc.update("INSERT INTO core_v2_library.audio_tracks (entity_id, duration_ms) VALUES (?,?)", id, durationMs)
        return id.toString()
    }

    private fun reportPlayThenStop(
        itemId: String,
        positionMs: Long,
    ) {
        assertEquals(204, post("/Sessions/Playing", mapOf("ItemId" to itemId, "PositionTicks" to 0L), token).response.status)
        val stop = post("/Sessions/Playing/Stopped", mapOf("ItemId" to itemId, "PositionTicks" to positionMs * 10_000), token)
        assertEquals(204, stop.response.status)
    }

    private fun historyRow(itemId: String): Map<String, Any?> =
        jdbc.queryForMap(
            "SELECT duration_ms, played_ms, completed, skipped, source, device_id " +
                "FROM core_v2_playback.play_history WHERE user_id = ? AND item_id = ?",
            userId,
            itemId,
        )

    @Test
    fun `stop past half the track records completed with duration and device`() {
        val trackId = seedTrack()
        reportPlayThenStop(trackId, positionMs = 60_000)

        val row = historyRow(trackId)
        assertEquals(100_000L, row["duration_ms"], "runtime known at the call site must be persisted, not nulled")
        assertEquals(60_000L, row["played_ms"])
        assertEquals(true, row["completed"], "60% of the track is past the half-way completion bar")
        assertEquals(false, row["skipped"])
        assertEquals(deviceId, row["device_id"], "reporting device must be recorded")
        assertNull(row["source"], "no adaptive session and no client-sent source means NULL, never a guess")
    }

    @Test
    fun `stop at 40 percent records skipped`() {
        val trackId = seedTrack()
        reportPlayThenStop(trackId, positionMs = 40_000)

        val row = historyRow(trackId)
        assertEquals(false, row["completed"])
        assertEquals(true, row["skipped"])
    }

    @Test
    fun `sub-3s stop is queue-surfing noise with neither flag`() {
        val trackId = seedTrack()
        reportPlayThenStop(trackId, positionMs = 2_000)

        val row = historyRow(trackId)
        assertEquals(false, row["completed"])
        assertEquals(false, row["skipped"], "sub-3s stops are navigation noise, not preference")
    }

    @Test
    fun `long wall-clock partial play of a known track is a skip not a completion`() {
        // Old rule stamped completed=true on elapsed > 240s regardless of position, and
        // skipped=true on a playbackStarts cache miss (elapsed ~ 0) regardless of position.
        val trackId = seedTrack()
        val now = Instant.now()
        scrobbleService.recordScrobble(
            userId = UserId(userId),
            trackId = TrackId(trackId),
            startedAt = now.minusSeconds(300),
            stoppedAt = now,
            positionMs = 40_000,
            runTimeMs = 100_000,
        )

        val row = historyRow(trackId)
        assertEquals(false, row["completed"], "wall-clock must not complete a known track stopped at 40%")
        assertEquals(true, row["skipped"])
    }

    @Test
    fun `wall-clock backstop still completes when runtime is unknown`() {
        val trackId = UUID.randomUUID().toString()
        val now = Instant.now()
        scrobbleService.recordScrobble(
            userId = UserId(userId),
            trackId = TrackId(trackId),
            startedAt = now.minusSeconds(300),
            stoppedAt = now,
            positionMs = 200_000,
            runTimeMs = 0,
        )

        val row = historyRow(trackId)
        assertEquals(0L, row["duration_ms"], "unknown runtime must not fabricate a duration")
        assertEquals(true, row["completed"])
        assertEquals(false, row["skipped"])
    }

    @Test
    fun `a track served from the adaptive queue is tagged adaptive, a hand-picked one is not`() {
        val sessionId = seedActiveSession()
        val fromRadio = seedTrack()
        val handPicked = seedTrack()
        // Only the radio track is actually in the adaptive queue; the hand-picked one is played while
        // the same session happens to be open. Provenance, not session-existence, decides the tag —
        // otherwise manual listening pollutes the adaptive skip metric and makes it un-actionable.
        enqueueAdaptive(sessionId, fromRadio, position = 0)

        reportPlayThenStop(fromRadio, positionMs = 60_000)
        reportPlayThenStop(handPicked, positionMs = 60_000)

        assertEquals("adaptive", historyRow(fromRadio)["source"], "a track from the radio queue is adaptive")
        assertNull(historyRow(handPicked)["source"], "a hand-picked track during a session is not adaptive")
    }

    private fun seedActiveSession(): UUID {
        val id = UUID.randomUUID()
        val ts = Timestamp.from(Instant.now())
        jdbc.update(
            "INSERT INTO core_v2_adaptive.listening_sessions " +
                "(id, user_id, state, started_at, last_activity_at, attention_mode, mood_tags, seed_genres) " +
                "VALUES (?,?,?,?,?,?,'{}','{}')",
            id,
            UUID.fromString(userId),
            "ACTIVE",
            ts,
            ts,
            "focus",
        )
        return id
    }

    private fun enqueueAdaptive(
        sessionId: UUID,
        trackId: String,
        position: Int,
    ) {
        jdbc.update(
            "INSERT INTO core_v2_adaptive.adaptive_queue (id, session_id, track_id, position, intent_label, added_at) " +
                "VALUES (?,?,?,?,?,?)",
            UUID.randomUUID(),
            sessionId,
            UUID.fromString(trackId),
            position,
            "radio",
            Timestamp.from(Instant.now()),
        )
    }
}
