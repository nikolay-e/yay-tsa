package dev.yaytsa.app.integration

import dev.yaytsa.adapterjellyfin.DeviceNowPlayingResolver
import dev.yaytsa.application.playback.PlaybackUseCases
import dev.yaytsa.domain.playback.AcquireLease
import dev.yaytsa.domain.playback.AddToQueue
import dev.yaytsa.domain.playback.Play
import dev.yaytsa.domain.playback.QueueEntry
import dev.yaytsa.domain.playback.QueueEntryId
import dev.yaytsa.domain.playback.SessionId
import dev.yaytsa.shared.AggregateVersion
import dev.yaytsa.shared.CommandContext
import dev.yaytsa.shared.CommandResult
import dev.yaytsa.shared.DeviceId
import dev.yaytsa.shared.IdempotencyKey
import dev.yaytsa.shared.ProtocolId
import dev.yaytsa.shared.TrackId
import dev.yaytsa.shared.UserId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import java.time.Duration
import java.time.Instant
import java.util.UUID

class DeviceNowPlayingIntegrationTest : HttpIntegrationTestBase() {
    @Autowired
    lateinit var playbackUseCases: PlaybackUseCases

    @Autowired
    lateinit var nowPlayingResolver: DeviceNowPlayingResolver

    @Autowired
    lateinit var jdbc: JdbcTemplate

    private fun seedTrack(name: String): String {
        val id = UUID.randomUUID()
        jdbc.update(
            "INSERT INTO core_v2_library.entities (id, entity_type, name, sort_name, source_path) VALUES (?,?,?,?,?)",
            id,
            "TRACK",
            name,
            name.lowercase(),
            "NowPlaying/$id.flac",
        )
        jdbc.update("INSERT INTO core_v2_library.audio_tracks (entity_id, duration_ms) VALUES (?,?)", id, 200_000L)
        return id.toString()
    }

    private fun ctx(
        uid: UserId,
        version: AggregateVersion,
    ) = CommandContext(uid, ProtocolId("JELLYFIN"), Instant.now(), IdempotencyKey(UUID.randomUUID().toString()), version)

    @Test
    fun `device now-playing resolves the current track for a playing session`() {
        val uid = UserId(UUID.randomUUID().toString())
        val sid = SessionId(UUID.randomUUID().toString())
        val did = DeviceId("device-${UUID.randomUUID().toString().take(8)}")
        val trackId = seedTrack("Now Playing Track ${UUID.randomUUID().toString().take(6)}")
        val entryId = QueueEntryId(UUID.randomUUID().toString())

        val afterLease = playbackUseCases.execute(AcquireLease(sid, did, Duration.ofMinutes(5)), ctx(uid, AggregateVersion.INITIAL))
        check(afterLease is CommandResult.Success) { "lease must acquire: $afterLease" }
        val afterQueue =
            playbackUseCases.execute(
                AddToQueue(sid, did, listOf(QueueEntry(entryId, TrackId(trackId)))),
                ctx(uid, afterLease.newVersion),
            )
        check(afterQueue is CommandResult.Success) { "queue add must succeed: $afterQueue" }
        val afterPlay = playbackUseCases.execute(Play(sid, did, entryId), ctx(uid, afterQueue.newVersion))
        check(afterPlay is CommandResult.Success) { "play must succeed: $afterPlay" }

        val nowPlaying = nowPlayingResolver.resolve(uid, sid)
        assertEquals(trackId, nowPlaying.nowPlayingItemId, "resolver must surface the current track id")
        assertEquals("PLAYING", nowPlaying.playbackState)
        assertEquals(did.value, nowPlaying.controllingDeviceId, "resolver must report the lease-owning device")
        assertEquals(true, nowPlaying.nowPlayingItemName?.startsWith("Now Playing Track"), "resolver must resolve the library name")
    }
}
