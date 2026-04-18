package dev.yaytsa.persistence.playback

import dev.yaytsa.application.playback.port.PlaybackSessionRepository
import dev.yaytsa.application.shared.port.OptimisticLockException
import dev.yaytsa.domain.playback.DeviceId
import dev.yaytsa.domain.playback.PlaybackLease
import dev.yaytsa.domain.playback.PlaybackSessionAggregate
import dev.yaytsa.domain.playback.PlaybackState
import dev.yaytsa.domain.playback.QueueEntry
import dev.yaytsa.domain.playback.QueueEntryId
import dev.yaytsa.domain.playback.SessionId
import dev.yaytsa.shared.AggregateVersion
import dev.yaytsa.shared.TrackId
import dev.yaytsa.shared.UserId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

class PlaybackSessionRepositoryTest : AbstractPersistenceTest() {
    @Autowired
    lateinit var repository: PlaybackSessionRepository

    @Test
    fun `save and find round-trip with queue and lease`() {
        val userId = UserId(UUID.randomUUID().toString())
        val sessionId = SessionId(UUID.randomUUID().toString())
        val now = Instant.now().truncatedTo(ChronoUnit.MILLIS)
        val aggregate = sessionAggregate(userId, sessionId, now)

        repository.save(aggregate)

        val found = repository.find(userId, sessionId)
        assertNotNull(found)
        assertEquals(userId, found!!.userId)
        assertEquals(sessionId, found.sessionId)
        assertEquals(PlaybackState.PLAYING, found.playbackState)
        assertEquals(Duration.ofMillis(30000), found.lastKnownPosition)
        assertEquals(now, found.lastKnownAt)
        assertEquals(2, found.queue.size)
        assertEquals(TrackId("track-x"), found.queue[0].trackId)
        assertEquals(TrackId("track-y"), found.queue[1].trackId)
        assertNotNull(found.currentEntryId)
        assertNotNull(found.lease)
        assertEquals(DeviceId("device-1"), found.lease!!.owner)
    }

    @Test
    fun `find returns null for non-existent session`() {
        val found =
            repository.find(
                UserId(UUID.randomUUID().toString()),
                SessionId(UUID.randomUUID().toString()),
            )
        assertNull(found)
    }

    @Test
    fun `save empty session without queue or lease`() {
        val userId = UserId(UUID.randomUUID().toString())
        val sessionId = SessionId(UUID.randomUUID().toString())
        val now = Instant.now().truncatedTo(ChronoUnit.MILLIS)
        val aggregate = PlaybackSessionAggregate.empty(userId, sessionId, now)

        repository.save(aggregate)

        val found = repository.find(userId, sessionId)
        assertNotNull(found)
        assertEquals(PlaybackState.STOPPED, found!!.playbackState)
        assertEquals(Duration.ZERO, found.lastKnownPosition)
        assertEquals(0, found.queue.size)
        assertNull(found.currentEntryId)
        assertNull(found.lease)
    }

    @Test
    fun `update with OCC increments version`() {
        val userId = UserId(UUID.randomUUID().toString())
        val sessionId = SessionId(UUID.randomUUID().toString())
        val now = Instant.now().truncatedTo(ChronoUnit.MILLIS)
        val aggregate = sessionAggregate(userId, sessionId, now)

        repository.save(aggregate)

        val loaded = repository.find(userId, sessionId)!!
        assertEquals(AggregateVersion.INITIAL, loaded.version)

        val updated =
            loaded.copy(
                playbackState = PlaybackState.PAUSED,
                version = loaded.version.next(),
            )
        repository.save(updated)

        val reloaded = repository.find(userId, sessionId)!!
        assertEquals(PlaybackState.PAUSED, reloaded.playbackState)
        assertEquals(AggregateVersion(1), reloaded.version)
    }

    @Test
    fun `concurrent save with stale version is rejected`() {
        val userId = UserId(UUID.randomUUID().toString())
        val sessionId = SessionId(UUID.randomUUID().toString())
        val now = Instant.now().truncatedTo(ChronoUnit.MILLIS)
        val aggregate = sessionAggregate(userId, sessionId, now)

        repository.save(aggregate)

        val loaded = repository.find(userId, sessionId)!!

        // First update succeeds
        val update1 =
            loaded.copy(
                playbackState = PlaybackState.PAUSED,
                version = loaded.version.next(),
            )
        repository.save(update1)

        // Second update with stale version should fail
        val staleUpdate =
            loaded.copy(
                playbackState = PlaybackState.STOPPED,
                version = loaded.version.next(),
            )
        assertThrows<OptimisticLockException> {
            repository.save(staleUpdate)
        }
    }

    @Test
    fun `save session with lease round-trip preserves lease fields`() {
        val userId = UserId(UUID.randomUUID().toString())
        val sessionId = SessionId(UUID.randomUUID().toString())
        val now = Instant.now().truncatedTo(ChronoUnit.MILLIS)
        val leaseExpiry = now.plus(10, ChronoUnit.MINUTES)
        val aggregate =
            PlaybackSessionAggregate(
                userId = userId,
                sessionId = sessionId,
                queue = emptyList(),
                currentEntryId = null,
                playbackState = PlaybackState.PAUSED,
                lastKnownPosition = Duration.ZERO,
                lastKnownAt = now,
                lease =
                    PlaybackLease(
                        owner = DeviceId("my-speaker"),
                        expiresAt = leaseExpiry,
                    ),
                version = AggregateVersion.INITIAL,
            )

        repository.save(aggregate)

        val found = repository.find(userId, sessionId)
        assertNotNull(found)
        assertNotNull(found!!.lease)
        assertEquals(DeviceId("my-speaker"), found.lease!!.owner)
        assertEquals(leaseExpiry, found.lease!!.expiresAt)
    }

    @Test
    fun `save session with lastKnownPosition and lastKnownAt round-trip`() {
        val userId = UserId(UUID.randomUUID().toString())
        val sessionId = SessionId(UUID.randomUUID().toString())
        val now = Instant.now().truncatedTo(ChronoUnit.MILLIS)
        val position = Duration.ofMillis(123456)
        val aggregate =
            PlaybackSessionAggregate(
                userId = userId,
                sessionId = sessionId,
                queue = emptyList(),
                currentEntryId = null,
                playbackState = PlaybackState.PLAYING,
                lastKnownPosition = position,
                lastKnownAt = now,
                lease = null,
                version = AggregateVersion.INITIAL,
            )

        repository.save(aggregate)

        val found = repository.find(userId, sessionId)
        assertNotNull(found)
        assertEquals(position, found!!.lastKnownPosition)
        assertEquals(now, found.lastKnownAt)
        assertEquals(PlaybackState.PLAYING, found.playbackState)
    }

    @Test
    fun `queue entry ordering is preserved across save`() {
        val userId = UserId(UUID.randomUUID().toString())
        val sessionId = SessionId(UUID.randomUUID().toString())
        val now = Instant.now().truncatedTo(ChronoUnit.MILLIS)
        val queueEntries =
            (0 until 8).map { i ->
                QueueEntry(QueueEntryId(UUID.randomUUID().toString()), TrackId("track-$i"))
            }
        val aggregate =
            PlaybackSessionAggregate(
                userId = userId,
                sessionId = sessionId,
                queue = queueEntries,
                currentEntryId = queueEntries[0].id,
                playbackState = PlaybackState.PLAYING,
                lastKnownPosition = Duration.ZERO,
                lastKnownAt = now,
                lease = null,
                version = AggregateVersion.INITIAL,
            )

        repository.save(aggregate)

        val found = repository.find(userId, sessionId)
        assertNotNull(found)
        assertEquals(8, found!!.queue.size)
        for (i in 0 until 8) {
            assertEquals(TrackId("track-$i"), found.queue[i].trackId)
            assertEquals(queueEntries[i].id, found.queue[i].id)
        }
        assertEquals(queueEntries[0].id, found.currentEntryId)
    }

    private fun sessionAggregate(
        userId: UserId,
        sessionId: SessionId,
        now: Instant,
    ): PlaybackSessionAggregate {
        val entryId1 = QueueEntryId(UUID.randomUUID().toString())
        val entryId2 = QueueEntryId(UUID.randomUUID().toString())
        return PlaybackSessionAggregate(
            userId = userId,
            sessionId = sessionId,
            queue =
                listOf(
                    QueueEntry(entryId1, TrackId("track-x")),
                    QueueEntry(entryId2, TrackId("track-y")),
                ),
            currentEntryId = entryId1,
            playbackState = PlaybackState.PLAYING,
            lastKnownPosition = Duration.ofMillis(30000),
            lastKnownAt = now,
            lease =
                PlaybackLease(
                    owner = DeviceId("device-1"),
                    expiresAt = now.plus(5, ChronoUnit.MINUTES),
                ),
            version = AggregateVersion.INITIAL,
        )
    }
}
