package dev.yaytsa.app.integration

import dev.yaytsa.application.auth.AuthUseCases
import dev.yaytsa.application.playback.PlaybackUseCases
import dev.yaytsa.domain.auth.ApiTokenId
import dev.yaytsa.domain.auth.CreateApiToken
import dev.yaytsa.domain.auth.CreateUser
import dev.yaytsa.domain.playback.AcquireLease
import dev.yaytsa.domain.playback.AddToQueue
import dev.yaytsa.domain.playback.Pause
import dev.yaytsa.domain.playback.Play
import dev.yaytsa.domain.playback.QueueEntry
import dev.yaytsa.domain.playback.QueueEntryId
import dev.yaytsa.domain.playback.SessionId
import dev.yaytsa.shared.AggregateVersion
import dev.yaytsa.shared.CommandContext
import dev.yaytsa.shared.CommandResult
import dev.yaytsa.shared.DeviceId
import dev.yaytsa.shared.Failure
import dev.yaytsa.shared.IdempotencyKey
import dev.yaytsa.shared.ProtocolId
import dev.yaytsa.shared.TrackId
import dev.yaytsa.shared.UserId
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.sql.DataSource

class PlaybackOccConflictIntegrationTest : HttpIntegrationTestBase() {
    @Autowired
    lateinit var authUseCases: AuthUseCases

    @Autowired
    lateinit var playbackUseCases: PlaybackUseCases

    @Autowired
    lateinit var jdbc: JdbcTemplate

    @Autowired
    lateinit var dataSource: DataSource

    private fun ctx(
        uid: UserId,
        version: AggregateVersion,
    ) = CommandContext(uid, ProtocolId("JELLYFIN"), Instant.now(), IdempotencyKey(UUID.randomUUID().toString()), version)

    private fun seedTrack(): String {
        val id = UUID.randomUUID()
        jdbc.update(
            "INSERT INTO core_v2_library.entities (id, entity_type, name, sort_name, source_path) VALUES (?,?,?,?,?)",
            id,
            "TRACK",
            "OccTrack-${id.toString().take(6)}",
            "occtrack",
            "OccConflict/$id.flac",
        )
        jdbc.update("INSERT INTO core_v2_library.audio_tracks (entity_id, duration_ms) VALUES (?,?)", id, 200_000L)
        return id.toString()
    }

    @Test
    fun `stale expected version is rejected with conflict and the first writer's state stands`() {
        val uid = UserId(UUID.randomUUID().toString())
        val sid = SessionId(UUID.randomUUID().toString())
        val device = DeviceId("occ-${UUID.randomUUID().toString().take(8)}")
        val entryId = QueueEntryId(UUID.randomUUID().toString())

        val afterLease = playbackUseCases.execute(AcquireLease(sid, device, Duration.ofMinutes(5)), ctx(uid, AggregateVersion.INITIAL))
        check(afterLease is CommandResult.Success)
        val afterQueue =
            playbackUseCases.execute(
                AddToQueue(sid, device, listOf(QueueEntry(entryId, TrackId(seedTrack())))),
                ctx(uid, afterLease.newVersion),
            )
        check(afterQueue is CommandResult.Success)
        val afterPlay = playbackUseCases.execute(Play(sid, device, entryId), ctx(uid, afterQueue.newVersion))
        check(afterPlay is CommandResult.Success)

        val staleWrite = playbackUseCases.execute(Pause(sid, device), ctx(uid, afterQueue.newVersion))

        assertTrue(staleWrite is CommandResult.Failed, "stale expected version must not be applied: $staleWrite")
        val failure = (staleWrite as CommandResult.Failed).failure
        assertTrue(failure is Failure.Conflict, "stale version must surface as Failure.Conflict, was $failure")

        val persisted = playbackUseCases.getPlaybackState(uid, sid)!!
        assertEquals("PLAYING", persisted.playbackState.name, "stale writer must not overwrite the first writer's state")
        assertEquals(afterPlay.newVersion, persisted.version, "aggregate version must reflect only the first writer")

        val freshWrite = playbackUseCases.execute(Pause(sid, device), ctx(uid, persisted.version))
        assertTrue(freshWrite is CommandResult.Success, "same command with the fresh version must apply: $freshWrite")
    }

    @Test
    fun `concurrent writer between read and commit surfaces as rfc7807 conflict over http`() {
        val userId = UUID.randomUUID().toString()
        val token = UUID.randomUUID().toString()
        val deviceA = "occ-a-${UUID.randomUUID().toString().take(8)}"
        val uid = UserId(userId)
        val now = Instant.now()
        authUseCases.execute(
            CreateUser(uid, "occ-${userId.take(8)}", "testpassword", "Test", null, false),
            CommandContext(uid, ProtocolId("JELLYFIN"), now, IdempotencyKey(UUID.randomUUID().toString()), AggregateVersion.INITIAL),
        )
        authUseCases.execute(
            CreateApiToken(uid, ApiTokenId(UUID.randomUUID().toString()), token, DeviceId(deviceA), "Device A", null),
            CommandContext(uid, ProtocolId("JELLYFIN"), now, IdempotencyKey(UUID.randomUUID().toString()), AggregateVersion(1)),
        )

        val sid = SessionId(UUID.randomUUID().toString())
        val afterLease = playbackUseCases.execute(AcquireLease(sid, DeviceId(deviceA), Duration.ofMinutes(5)), ctx(uid, AggregateVersion.INITIAL))
        check(afterLease is CommandResult.Success)

        val executor = Executors.newSingleThreadExecutor()
        try {
            dataSource.connection.use { winner ->
                winner.autoCommit = false
                winner
                    .prepareStatement("UPDATE core_v2_playback.playback_sessions SET version = version + 1 WHERE user_id = ? AND session_id = ?")
                    .use { statement ->
                        statement.setString(1, userId)
                        statement.setString(2, sid.value)
                        assertEquals(1, statement.executeUpdate())
                    }

                val staleTransfer =
                    executor.submit<org.springframework.test.web.servlet.MvcResult> {
                        post("/v1/me/devices/${sid.value}/transfer", mapOf("toDeviceId" to "occ-b-target"), token)
                    }

                await().atMost(15, TimeUnit.SECONDS).until {
                    jdbc.queryForObject(
                        "SELECT COUNT(*) FROM pg_stat_activity " +
                            "WHERE state = 'active' AND wait_event_type = 'Lock' " +
                            "AND query LIKE '%playback_sessions%' AND query NOT LIKE '%pg_stat_activity%'",
                        Int::class.java,
                    )!! > 0
                }
                winner.commit()

                val response = staleTransfer.get(15, TimeUnit.SECONDS).response
                assertEquals(409, response.status, "losing writer must get 409, body: ${response.contentAsString}")
                assertTrue(
                    response.contentType!!.startsWith("application/problem+json"),
                    "conflict must be an RFC 7807 problem, was ${response.contentType}",
                )
                val body = objectMapper.readTree(response.contentAsString)
                assertEquals(409, body.get("status").asInt())
                assertEquals("Conflict", body.get("title").asText())
                assertTrue(body.get("detail").asText().contains("modified concurrently"), body.toString())
            }
        } finally {
            executor.shutdownNow()
        }

        val owner =
            jdbc.queryForObject(
                "SELECT lease_owner FROM core_v2_playback.playback_sessions WHERE user_id = ? AND session_id = ?",
                String::class.java,
                userId,
                sid.value,
            )
        assertEquals(deviceA, owner, "losing transfer must not move the lease")
    }
}
