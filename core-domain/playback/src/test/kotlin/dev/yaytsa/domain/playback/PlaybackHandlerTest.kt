package dev.yaytsa.domain.playback

import dev.yaytsa.shared.AggregateVersion
import dev.yaytsa.shared.CommandContext
import dev.yaytsa.shared.CommandResult
import dev.yaytsa.shared.Failure
import dev.yaytsa.shared.IdempotencyKey
import dev.yaytsa.shared.ProtocolId
import dev.yaytsa.shared.TrackId
import dev.yaytsa.shared.UserId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.time.Duration
import java.time.Instant

class PlaybackHandlerTest :
    FunSpec({
        val userId = UserId("user-1")
        val sessionId = SessionId("session-1")
        val devA = DeviceId("dev-A")
        val devB = DeviceId("dev-B")
        val now = Instant.parse("2025-01-01T12:00:00Z")
        val t1 = TrackId("t1")

        fun ctx(v: AggregateVersion = AggregateVersion.INITIAL) = CommandContext(userId, ProtocolId("JELLYFIN"), now, IdempotencyKey("k"), v)

        fun deps(ids: Set<TrackId> = emptySet()) = PlaybackDeps(ids, Duration.ofMinutes(3))

        fun empty() = PlaybackSessionAggregate.empty(userId, sessionId, now)

        fun withLease(
            device: DeviceId = devA,
            expiresAt: Instant = now.plusSeconds(60),
            v: AggregateVersion = AggregateVersion(1),
        ) = empty().copy(lease = PlaybackLease(device, expiresAt), version = v)

        test("AcquireLease on empty session") {
            val r = PlaybackHandler.handle(empty(), AcquireLease(sessionId, devA, Duration.ofSeconds(60)), ctx(), deps())
            r.shouldBeInstanceOf<CommandResult.Success<PlaybackSessionAggregate>>()
            r.value.lease!!.owner shouldBe devA
        }

        test("AcquireLease fails when active lease held by other device") {
            val s = withLease(devA)
            val r = PlaybackHandler.handle(s, AcquireLease(sessionId, devB, Duration.ofSeconds(60)), ctx(s.version), deps())
            r.shouldBeInstanceOf<CommandResult.Failed>()
            r.failure.shouldBeInstanceOf<Failure.Unauthorized>()
        }

        test("AcquireLease succeeds when lease expired") {
            val s = withLease(devA, expiresAt = now.minusSeconds(1))
            val r = PlaybackHandler.handle(s, AcquireLease(sessionId, devB, Duration.ofSeconds(60)), ctx(s.version), deps())
            r.shouldBeInstanceOf<CommandResult.Success<PlaybackSessionAggregate>>()
            r.value.lease!!.owner shouldBe devB
        }

        test("ReleaseLease pauses and clears lease") {
            val s = withLease().copy(playbackState = PlaybackState.PLAYING)
            val r = PlaybackHandler.handle(s, ReleaseLease(sessionId, devA), ctx(s.version), deps())
            r.shouldBeInstanceOf<CommandResult.Success<PlaybackSessionAggregate>>()
            r.value.lease shouldBe null
            r.value.playbackState shouldBe PlaybackState.PAUSED
        }

        test("RefreshLease on expired fails") {
            val s = withLease(expiresAt = now.minusSeconds(1))
            val r = PlaybackHandler.handle(s, RefreshLease(sessionId, devA, Duration.ofSeconds(60)), ctx(s.version), deps())
            r.shouldBeInstanceOf<CommandResult.Failed>()
            r.failure.shouldBeInstanceOf<Failure.Unauthorized>()
        }

        test("AddToQueue with valid tracks succeeds") {
            val s = withLease()
            val entry = QueueEntry(QueueEntryId("e1"), t1)
            val r = PlaybackHandler.handle(s, AddToQueue(sessionId, devA, listOf(entry)), ctx(s.version), deps(setOf(t1)))
            r.shouldBeInstanceOf<CommandResult.Success<PlaybackSessionAggregate>>()
            r.value.queue.size shouldBe 1
        }

        test("AddToQueue without lease fails") {
            val entry = QueueEntry(QueueEntryId("e1"), t1)
            val r = PlaybackHandler.handle(empty(), AddToQueue(sessionId, devA, listOf(entry)), ctx(), deps(setOf(t1)))
            r.shouldBeInstanceOf<CommandResult.Failed>()
            r.failure.shouldBeInstanceOf<Failure.Unauthorized>()
        }

        test("AddToQueue from non-owner device fails") {
            val s = withLease(devA)
            val entry = QueueEntry(QueueEntryId("e1"), t1)
            val r = PlaybackHandler.handle(s, AddToQueue(sessionId, devB, listOf(entry)), ctx(s.version), deps(setOf(t1)))
            r.shouldBeInstanceOf<CommandResult.Failed>()
            r.failure.shouldBeInstanceOf<Failure.Unauthorized>()
        }

        test("AddToQueue with unknown track fails") {
            val s = withLease()
            val entry = QueueEntry(QueueEntryId("e1"), t1)
            val r = PlaybackHandler.handle(s, AddToQueue(sessionId, devA, listOf(entry)), ctx(s.version), deps())
            r.shouldBeInstanceOf<CommandResult.Failed>()
            r.failure.shouldBeInstanceOf<Failure.InvariantViolation>()
        }

        test("RemoveFromQueue from non-owner device fails") {
            val entry = QueueEntry(QueueEntryId("e1"), t1)
            val s = withLease(devA).copy(queue = listOf(entry))
            val r = PlaybackHandler.handle(s, RemoveFromQueue(sessionId, devB, QueueEntryId("e1")), ctx(s.version), deps())
            r.shouldBeInstanceOf<CommandResult.Failed>()
            r.failure.shouldBeInstanceOf<Failure.Unauthorized>()
        }

        test("Play starts playback") {
            val entry = QueueEntry(QueueEntryId("e1"), t1)
            val s = withLease().copy(queue = listOf(entry))
            val r = PlaybackHandler.handle(s, Play(sessionId, devA), ctx(s.version), deps())
            r.shouldBeInstanceOf<CommandResult.Success<PlaybackSessionAggregate>>()
            r.value.playbackState shouldBe PlaybackState.PLAYING
            r.value.currentEntryId shouldBe entry.id
        }

        test("Pause from non-owner device fails") {
            val entry = QueueEntry(QueueEntryId("e1"), t1)
            val s = withLease(devA).copy(queue = listOf(entry), currentEntryId = entry.id, playbackState = PlaybackState.PLAYING)
            val r = PlaybackHandler.handle(s, Pause(sessionId, devB), ctx(s.version), deps())
            r.shouldBeInstanceOf<CommandResult.Failed>()
            r.failure.shouldBeInstanceOf<Failure.Unauthorized>()
        }

        test("Pause records position via lastKnownAt") {
            val entry = QueueEntry(QueueEntryId("e1"), t1)
            val s =
                withLease().copy(
                    queue = listOf(entry),
                    currentEntryId = entry.id,
                    playbackState = PlaybackState.PLAYING,
                    lastKnownAt = now.minusSeconds(30),
                )
            val r = PlaybackHandler.handle(s, Pause(sessionId, devA), ctx(s.version), deps())
            r.shouldBeInstanceOf<CommandResult.Success<PlaybackSessionAggregate>>()
            r.value.playbackState shouldBe PlaybackState.PAUSED
            r.value.lastKnownPosition shouldBe Duration.ofSeconds(30)
        }

        test("Seek updates position") {
            val entry = QueueEntry(QueueEntryId("e1"), t1)
            val s = withLease().copy(queue = listOf(entry), currentEntryId = entry.id, playbackState = PlaybackState.PLAYING)
            val r = PlaybackHandler.handle(s, Seek(sessionId, devA, Duration.ofSeconds(45)), ctx(s.version), deps(setOf(t1)))
            r.shouldBeInstanceOf<CommandResult.Success<PlaybackSessionAggregate>>()
            r.value.lastKnownPosition shouldBe Duration.ofSeconds(45)
        }

        test("Seek past track end fails") {
            val entry = QueueEntry(QueueEntryId("e1"), t1)
            val s = withLease().copy(queue = listOf(entry), currentEntryId = entry.id, playbackState = PlaybackState.PLAYING)
            val r = PlaybackHandler.handle(s, Seek(sessionId, devA, Duration.ofMinutes(10)), ctx(s.version), deps(setOf(t1)))
            r.shouldBeInstanceOf<CommandResult.Failed>()
            r.failure.shouldBeInstanceOf<Failure.InvariantViolation>()
        }

        test("SkipNext advances") {
            val entries = listOf(QueueEntry(QueueEntryId("e1"), t1), QueueEntry(QueueEntryId("e2"), TrackId("t2")))
            val s = withLease().copy(queue = entries, currentEntryId = entries[0].id, playbackState = PlaybackState.PLAYING)
            val r = PlaybackHandler.handle(s, SkipNext(sessionId, devA), ctx(s.version), deps())
            r.shouldBeInstanceOf<CommandResult.Success<PlaybackSessionAggregate>>()
            r.value.currentEntryId shouldBe entries[1].id
        }

        test("SkipNext at end fails") {
            val entry = QueueEntry(QueueEntryId("e1"), t1)
            val s = withLease().copy(queue = listOf(entry), currentEntryId = entry.id)
            val r = PlaybackHandler.handle(s, SkipNext(sessionId, devA), ctx(s.version), deps())
            r.shouldBeInstanceOf<CommandResult.Failed>()
            r.failure.shouldBeInstanceOf<Failure.InvariantViolation>()
        }

        test("StartPlaybackWithTracks atomic") {
            val entry = QueueEntry(QueueEntryId("e1"), t1)
            val r =
                PlaybackHandler.handle(
                    empty(),
                    StartPlaybackWithTracks(sessionId, devA, Duration.ofSeconds(60), listOf(entry)),
                    ctx(),
                    deps(setOf(t1)),
                )
            r.shouldBeInstanceOf<CommandResult.Success<PlaybackSessionAggregate>>()
            r.value.lease!!.owner shouldBe devA
            r.value.queue.size shouldBe 1
            r.value.playbackState shouldBe PlaybackState.PLAYING
        }

        test("computePosition adds elapsed time when PLAYING") {
            val s =
                empty().copy(
                    playbackState = PlaybackState.PLAYING,
                    lastKnownPosition = Duration.ofSeconds(10),
                    lastKnownAt = now,
                )
            s.computePosition(now.plusSeconds(5)) shouldBe Duration.ofSeconds(15)
        }

        test("computePosition returns lastKnownPosition when PAUSED") {
            val s =
                empty().copy(
                    playbackState = PlaybackState.PAUSED,
                    lastKnownPosition = Duration.ofSeconds(10),
                    lastKnownAt = now,
                )
            s.computePosition(now.plusSeconds(100)) shouldBe Duration.ofSeconds(10)
        }

        test("SkipPrevious goes to previous track") {
            val e1 = QueueEntry(QueueEntryId("e1"), t1)
            val e2 = QueueEntry(QueueEntryId("e2"), TrackId("t2"))
            val s =
                withLease().copy(
                    queue = listOf(e1, e2),
                    currentEntryId = e2.id,
                    playbackState = PlaybackState.PLAYING,
                )
            val r = PlaybackHandler.handle(s, SkipPrevious(sessionId, devA), ctx(s.version), deps())
            r.shouldBeInstanceOf<CommandResult.Success<PlaybackSessionAggregate>>()
            r.value.currentEntryId shouldBe e1.id
            r.value.playbackState shouldBe PlaybackState.PLAYING
        }

        test("SkipPrevious at first track fails") {
            val e1 = QueueEntry(QueueEntryId("e1"), t1)
            val s =
                withLease().copy(
                    queue = listOf(e1),
                    currentEntryId = e1.id,
                    playbackState = PlaybackState.PLAYING,
                )
            val r = PlaybackHandler.handle(s, SkipPrevious(sessionId, devA), ctx(s.version), deps())
            r.shouldBeInstanceOf<CommandResult.Failed>()
            r.failure.shouldBeInstanceOf<Failure.InvariantViolation>()
        }

        test("SkipPrevious with no current track fails") {
            val s = withLease().copy(queue = listOf(QueueEntry(QueueEntryId("e1"), t1)))
            val r = PlaybackHandler.handle(s, SkipPrevious(sessionId, devA), ctx(s.version), deps())
            r.shouldBeInstanceOf<CommandResult.Failed>()
            r.failure.shouldBeInstanceOf<Failure.InvariantViolation>()
        }

        test("RemoveFromQueue of current track stops and snapshots position") {
            val e1 = QueueEntry(QueueEntryId("e1"), t1)
            val playStart = now.minusSeconds(30)
            val s =
                withLease().copy(
                    queue = listOf(e1),
                    currentEntryId = e1.id,
                    playbackState = PlaybackState.PLAYING,
                    lastKnownPosition = Duration.ZERO,
                    lastKnownAt = playStart,
                )
            val r = PlaybackHandler.handle(s, RemoveFromQueue(sessionId, devA, e1.id), ctx(s.version), deps())
            r.shouldBeInstanceOf<CommandResult.Success<PlaybackSessionAggregate>>()
            r.value.currentEntryId shouldBe null
            r.value.playbackState shouldBe PlaybackState.STOPPED
            r.value.lastKnownPosition shouldBe Duration.ofSeconds(30)
        }

        test("Pause when stopped fails") {
            val s = withLease().copy(playbackState = PlaybackState.STOPPED)
            val r = PlaybackHandler.handle(s, Pause(sessionId, devA), ctx(s.version), deps())
            r.shouldBeInstanceOf<CommandResult.Failed>()
            r.failure.shouldBeInstanceOf<Failure.InvariantViolation>()
        }

        test("Seek when stopped fails") {
            val e1 = QueueEntry(QueueEntryId("e1"), t1)
            val s =
                withLease().copy(
                    queue = listOf(e1),
                    currentEntryId = e1.id,
                    playbackState = PlaybackState.STOPPED,
                )
            val r = PlaybackHandler.handle(s, Seek(sessionId, devA, Duration.ofSeconds(10)), ctx(s.version), deps(setOf(t1)))
            r.shouldBeInstanceOf<CommandResult.Failed>()
            r.failure.shouldBeInstanceOf<Failure.InvariantViolation>()
        }

        test("version mismatch returns Conflict") {
            val r =
                PlaybackHandler.handle(
                    withLease(v = AggregateVersion(5)),
                    Pause(sessionId, devA),
                    ctx(AggregateVersion(3)),
                    deps(),
                )
            r.shouldBeInstanceOf<CommandResult.Failed>()
            r.failure.shouldBeInstanceOf<Failure.Conflict>()
        }

        test("AcquireLease same device re-acquires own lease") {
            val s = withLease(device = devA, expiresAt = now.plusSeconds(60))
            val r = PlaybackHandler.handle(s, AcquireLease(sessionId, devA, Duration.ofSeconds(120)), ctx(s.version), deps())
            r.shouldBeInstanceOf<CommandResult.Success<PlaybackSessionAggregate>>()
            r.value.lease!!.owner shouldBe devA
        }

        test("ReleaseLease preserves STOPPED state") {
            val s = withLease().copy(playbackState = PlaybackState.STOPPED)
            val r = PlaybackHandler.handle(s, ReleaseLease(sessionId, devA), ctx(s.version), deps())
            r.shouldBeInstanceOf<CommandResult.Success<PlaybackSessionAggregate>>()
            r.value.playbackState shouldBe PlaybackState.STOPPED
            r.value.lease shouldBe null
        }

        test("RefreshLease from non-owner fails") {
            val s = withLease(device = devA)
            val r = PlaybackHandler.handle(s, RefreshLease(sessionId, devB, Duration.ofSeconds(60)), ctx(s.version), deps())
            r.shouldBeInstanceOf<CommandResult.Failed>()
            r.failure.shouldBeInstanceOf<Failure.Unauthorized>()
        }

        test("StartPlaybackWithTracks steals expired lease from another device") {
            val expired = withLease(device = devA, expiresAt = now.minusSeconds(1))
            val entry = QueueEntry(QueueEntryId("e1"), t1)
            val r =
                PlaybackHandler.handle(
                    expired,
                    StartPlaybackWithTracks(sessionId, devB, Duration.ofSeconds(60), listOf(entry)),
                    ctx(expired.version),
                    deps(setOf(t1)),
                )
            r.shouldBeInstanceOf<CommandResult.Success<PlaybackSessionAggregate>>()
            r.value.lease!!.owner shouldBe devB
            r.value.playbackState shouldBe PlaybackState.PLAYING
        }

        test("Play with absent entryId fails") {
            val entry = QueueEntry(QueueEntryId("e1"), t1)
            val s = withLease().copy(queue = listOf(entry), currentEntryId = entry.id, playbackState = PlaybackState.PLAYING)
            val r = PlaybackHandler.handle(s, Play(sessionId, devA, QueueEntryId("nonexistent")), ctx(s.version), deps())
            r.shouldBeInstanceOf<CommandResult.Failed>()
            r.failure.shouldBeInstanceOf<Failure.NotFound>()
        }

        test("Play with current entryId preserves position") {
            val entry = QueueEntry(QueueEntryId("e1"), t1)
            val s =
                withLease().copy(
                    queue = listOf(entry),
                    currentEntryId = entry.id,
                    playbackState = PlaybackState.PLAYING,
                    lastKnownPosition = Duration.ofSeconds(42),
                    lastKnownAt = now.minusSeconds(5),
                )
            val r = PlaybackHandler.handle(s, Play(sessionId, devA, entry.id), ctx(s.version), deps())
            r.shouldBeInstanceOf<CommandResult.Success<PlaybackSessionAggregate>>()
            r.value.lastKnownPosition shouldBe Duration.ofSeconds(42)
        }

        test("MoveQueueEntry reorders entries") {
            val e1 = QueueEntry(QueueEntryId("e1"), t1)
            val e2 = QueueEntry(QueueEntryId("e2"), TrackId("t2"))
            val e3 = QueueEntry(QueueEntryId("e3"), TrackId("t3"))
            val s = withLease().copy(queue = listOf(e1, e2, e3))
            val r = PlaybackHandler.handle(s, MoveQueueEntry(sessionId, devA, e3.id, 0), ctx(s.version), deps())
            r.shouldBeInstanceOf<CommandResult.Success<PlaybackSessionAggregate>>()
            r.value.queue.map { it.id } shouldBe listOf(e3.id, e1.id, e2.id)
        }

        test("ReplaceQueue replaces entire queue") {
            val e1 = QueueEntry(QueueEntryId("e1"), t1)
            val s = withLease().copy(queue = listOf(e1))
            val newEntry = QueueEntry(QueueEntryId("e2"), TrackId("t2"))
            val r = PlaybackHandler.handle(s, ReplaceQueue(sessionId, devA, listOf(newEntry)), ctx(s.version), deps(setOf(TrackId("t2"))))
            r.shouldBeInstanceOf<CommandResult.Success<PlaybackSessionAggregate>>()
            r.value.queue.size shouldBe 1
            r.value.queue[0].id shouldBe newEntry.id
        }

        test("ClearQueue empties the queue") {
            val e1 = QueueEntry(QueueEntryId("e1"), t1)
            val s = withLease().copy(queue = listOf(e1))
            val r = PlaybackHandler.handle(s, ClearQueue(sessionId, devA), ctx(s.version), deps())
            r.shouldBeInstanceOf<CommandResult.Success<PlaybackSessionAggregate>>()
            r.value.queue shouldBe emptyList()
        }

        test("Seek while paused succeeds") {
            val entry = QueueEntry(QueueEntryId("e1"), t1)
            val s = withLease().copy(queue = listOf(entry), currentEntryId = entry.id, playbackState = PlaybackState.PAUSED)
            val r = PlaybackHandler.handle(s, Seek(sessionId, devA, Duration.ofSeconds(30)), ctx(s.version), deps(setOf(t1)))
            r.shouldBeInstanceOf<CommandResult.Success<PlaybackSessionAggregate>>()
            r.value.lastKnownPosition shouldBe Duration.ofSeconds(30)
        }
    })
