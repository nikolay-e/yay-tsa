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
import dev.yaytsa.domain.playback.RefreshLease
import dev.yaytsa.domain.playback.ReleaseLease
import dev.yaytsa.domain.playback.Seek
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
import java.util.concurrent.TimeUnit

class PlaybackLeaseIntegrationTest : HttpIntegrationTestBase() {
    @Autowired
    lateinit var authUseCases: AuthUseCases

    @Autowired
    lateinit var playbackUseCases: PlaybackUseCases

    @Autowired
    lateinit var jdbc: JdbcTemplate

    private data class TwoDeviceUser(
        val uid: UserId,
        val deviceA: DeviceId,
        val deviceB: DeviceId,
        val tokenA: String,
    )

    private fun seedUserWithTwoDevices(prefix: String): TwoDeviceUser {
        val userId = UUID.randomUUID().toString()
        val uid = UserId(userId)
        val deviceA = DeviceId("$prefix-a-${UUID.randomUUID().toString().take(8)}")
        val deviceB = DeviceId("$prefix-b-${UUID.randomUUID().toString().take(8)}")
        val tokenA = UUID.randomUUID().toString()
        val now = Instant.now()
        authUseCases.execute(
            CreateUser(uid, "$prefix-${userId.take(8)}", "testpassword", "Test", null, false),
            CommandContext(uid, ProtocolId("JELLYFIN"), now, IdempotencyKey(UUID.randomUUID().toString()), AggregateVersion.INITIAL),
        )
        authUseCases.execute(
            CreateApiToken(uid, ApiTokenId(UUID.randomUUID().toString()), tokenA, deviceA, "Device A", null),
            CommandContext(uid, ProtocolId("JELLYFIN"), now, IdempotencyKey(UUID.randomUUID().toString()), AggregateVersion(1)),
        )
        authUseCases.execute(
            CreateApiToken(uid, ApiTokenId(UUID.randomUUID().toString()), UUID.randomUUID().toString(), deviceB, "Device B", null),
            CommandContext(uid, ProtocolId("JELLYFIN"), now, IdempotencyKey(UUID.randomUUID().toString()), AggregateVersion(2)),
        )
        return TwoDeviceUser(uid, deviceA, deviceB, tokenA)
    }

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
            "LeaseTrack-${id.toString().take(6)}",
            "leasetrack",
            "Lease/$id.flac",
        )
        jdbc.update("INSERT INTO core_v2_library.audio_tracks (entity_id, duration_ms) VALUES (?,?)", id, 200_000L)
        return id.toString()
    }

    private fun startPlayingOnDeviceA(
        user: TwoDeviceUser,
        sid: SessionId,
    ): AggregateVersion {
        val afterLease = playbackUseCases.execute(AcquireLease(sid, user.deviceA, Duration.ofMinutes(5)), ctx(user.uid, AggregateVersion.INITIAL))
        check(afterLease is CommandResult.Success) { "lease must acquire: $afterLease" }
        val entryId = QueueEntryId(UUID.randomUUID().toString())
        val afterQueue =
            playbackUseCases.execute(
                AddToQueue(sid, user.deviceA, listOf(QueueEntry(entryId, TrackId(seedTrack())))),
                ctx(user.uid, afterLease.newVersion),
            )
        check(afterQueue is CommandResult.Success) { "queue add must succeed: $afterQueue" }
        val afterPlay = playbackUseCases.execute(Play(sid, user.deviceA, entryId), ctx(user.uid, afterQueue.newVersion))
        check(afterPlay is CommandResult.Success) { "play must succeed: $afterPlay" }
        return afterPlay.newVersion
    }

    private fun currentVersion(
        user: TwoDeviceUser,
        sid: SessionId,
    ): AggregateVersion = playbackUseCases.getPlaybackState(user.uid, sid)!!.version

    @Test
    fun `device b command is denied while device a holds the lease`() {
        val user = seedUserWithTwoDevices("lease")
        val sid = SessionId(UUID.randomUUID().toString())
        val version = startPlayingOnDeviceA(user, sid)

        val denied = playbackUseCases.execute(Pause(sid, user.deviceB), ctx(user.uid, version))

        assertTrue(denied is CommandResult.Failed, "non-owner command must be denied: $denied")
        val failure = (denied as CommandResult.Failed).failure
        assertTrue(failure is Failure.Unauthorized, "lease denial must be Unauthorized, was $failure")
        assertTrue(failure.toString().contains("different device"), "denial must name the lease violation, was $failure")

        val persisted = playbackUseCases.getPlaybackState(user.uid, sid)!!
        assertEquals("PLAYING", persisted.playbackState.name, "denied command must not change playback state")
        assertEquals(user.deviceA, persisted.lease?.owner, "lease must stay with device a")
    }

    @Test
    fun `explicit transfer over http hands the lease to device b and locks out device a`() {
        val user = seedUserWithTwoDevices("xfer")
        val sid = SessionId(UUID.randomUUID().toString())
        startPlayingOnDeviceA(user, sid)

        val transfer = post("/v1/me/devices/${sid.value}/transfer", mapOf("toDeviceId" to user.deviceB.value), user.tokenA)
        assertEquals(200, transfer.response.status, transfer.response.contentAsString)
        val body = objectMapper.readTree(transfer.response.contentAsString)
        assertEquals(user.deviceB.value, body.get("deviceId").asText(), "transfer response must report the new owner")

        val lockedOut = playbackUseCases.execute(Pause(sid, user.deviceA), ctx(user.uid, currentVersion(user, sid)))
        assertTrue(lockedOut is CommandResult.Failed, "old owner must be locked out after transfer: $lockedOut")
        assertTrue((lockedOut as CommandResult.Failed).failure is Failure.Unauthorized)

        val newOwnerWrite = playbackUseCases.execute(Pause(sid, user.deviceB), ctx(user.uid, currentVersion(user, sid)))
        assertTrue(newOwnerWrite is CommandResult.Success, "new owner must control playback: $newOwnerWrite")
        assertEquals("PAUSED", playbackUseCases.getPlaybackState(user.uid, sid)!!.playbackState.name)
    }

    @Test
    fun `expired lease can be taken over by device b which then locks out device a`() {
        val user = seedUserWithTwoDevices("expiry")
        val sid = SessionId(UUID.randomUUID().toString())
        val version = startPlayingOnDeviceA(user, sid)
        val shortened =
            playbackUseCases.execute(RefreshLease(sid, user.deviceA, Duration.ofMillis(300)), ctx(user.uid, version))
        check(shortened is CommandResult.Success) { "lease shorten must succeed: $shortened" }

        await().atMost(10, TimeUnit.SECONDS).until {
            val takeover =
                playbackUseCases.execute(
                    AcquireLease(sid, user.deviceB, Duration.ofMinutes(5)),
                    ctx(user.uid, currentVersion(user, sid)),
                )
            takeover is CommandResult.Success
        }

        assertEquals(user.deviceB, playbackUseCases.getPlaybackState(user.uid, sid)!!.lease?.owner)

        val lockedOut = playbackUseCases.execute(Seek(sid, user.deviceA, Duration.ofSeconds(30)), ctx(user.uid, currentVersion(user, sid)))
        assertTrue(lockedOut is CommandResult.Failed, "old owner must be locked out after takeover: $lockedOut")
        assertTrue((lockedOut as CommandResult.Failed).failure is Failure.Unauthorized)
    }

    @Test
    fun `transfer with no active lease is a 409 problem response over http`() {
        val user = seedUserWithTwoDevices("nolease")
        val sid = SessionId(UUID.randomUUID().toString())
        val version = startPlayingOnDeviceA(user, sid)

        val released = playbackUseCases.execute(ReleaseLease(sid, user.deviceA), ctx(user.uid, version))
        check(released is CommandResult.Success) { "release must succeed: $released" }

        val transfer = post("/v1/me/devices/${sid.value}/transfer", mapOf("toDeviceId" to user.deviceB.value), user.tokenA)
        assertEquals(409, transfer.response.status, transfer.response.contentAsString)
        val body = objectMapper.readTree(transfer.response.contentAsString)
        assertEquals(409, body.get("status").asInt())
        assertTrue(body.get("detail").asText().contains("No active lease"), body.toString())
    }
}
