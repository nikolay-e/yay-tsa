package dev.yaytsa.domain.adaptive

import dev.yaytsa.shared.AggregateVersion
import dev.yaytsa.shared.CommandContext
import dev.yaytsa.shared.CommandResult
import dev.yaytsa.shared.EntityId
import dev.yaytsa.shared.Failure
import dev.yaytsa.shared.IdempotencyKey
import dev.yaytsa.shared.ProtocolId
import dev.yaytsa.shared.TrackId
import dev.yaytsa.shared.UserId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.time.Instant

class AdaptiveHandlerTest :
    FunSpec({
        val userId = UserId("user-1")
        val sessionId = ListeningSessionId("session-1")
        val now = Instant.parse("2025-01-01T12:00:00Z")
        val t1 = TrackId("t1")
        val t2 = TrackId("t2")
        val t3 = TrackId("t3")

        fun ctx(v: AggregateVersion = AggregateVersion.INITIAL) = CommandContext(userId, ProtocolId("JELLYFIN"), now, IdempotencyKey("k"), v)

        fun deps(ids: Set<TrackId> = emptySet()) = AdaptiveDeps(ids)

        fun startCmd() =
            StartListeningSession(
                sessionId = sessionId,
                attentionMode = "focus",
                seedTrackId = EntityId("seed-1"),
                seedGenres = listOf("jazz"),
            )

        fun started(v: AggregateVersion = AggregateVersion(1)) =
            AdaptiveSessionAggregate
                .start(
                    id = sessionId,
                    userId = userId,
                    attentionMode = "focus",
                    seedTrackId = EntityId("seed-1"),
                    seedGenres = listOf("jazz"),
                    now = now,
                ).copy(version = v)

        fun withQueue(
            entries: List<AdaptiveQueueEntryData>,
            queueVersion: Long = 1,
            v: AggregateVersion = AggregateVersion(2),
        ) = started(v).copy(queue = entries, queueVersion = queueVersion)

        fun entry(
            id: String,
            trackId: TrackId,
            position: Int,
            queueVersion: Long = 1,
        ) = AdaptiveQueueEntryData(
            id = AdaptiveQueueEntryId(id),
            trackId = trackId,
            position = position,
            addedReason = null,
            intentLabel = null,
            queueVersion = queueVersion,
            addedAt = now,
        )

        test("StartListeningSession creates new session") {
            val r = AdaptiveHandler.handle(null, startCmd(), ctx(), deps())
            r.shouldBeInstanceOf<CommandResult.Success<AdaptiveSessionAggregate>>()
            r.value.id shouldBe sessionId
            r.value.userId shouldBe userId
            r.value.state shouldBe SessionState.ACTIVE
            r.value.attentionMode shouldBe "focus"
            r.value.seedTrackId shouldBe EntityId("seed-1")
            r.value.seedGenres shouldBe listOf("jazz")
            r.value.queueVersion shouldBe 0
            r.newVersion shouldBe AggregateVersion(1)
        }

        test("StartListeningSession fails when session exists") {
            val r = AdaptiveHandler.handle(started(), startCmd(), ctx(AggregateVersion(1)), deps())
            r.shouldBeInstanceOf<CommandResult.Failed>()
            r.failure.shouldBeInstanceOf<Failure.InvariantViolation>()
        }

        test("EndListeningSession sets state to ENDED") {
            val s = started()
            val r = AdaptiveHandler.handle(s, EndListeningSession(sessionId, "great session"), ctx(s.version), deps())
            r.shouldBeInstanceOf<CommandResult.Success<AdaptiveSessionAggregate>>()
            r.value.state shouldBe SessionState.ENDED
            r.value.endedAt shouldBe now
            r.value.sessionSummary shouldBe "great session"
        }

        test("EndListeningSession fails when already ended") {
            val s = started().copy(state = SessionState.ENDED, endedAt = now)
            val r = AdaptiveHandler.handle(s, EndListeningSession(sessionId, null), ctx(s.version), deps())
            r.shouldBeInstanceOf<CommandResult.Failed>()
            r.failure.shouldBeInstanceOf<Failure.InvariantViolation>()
        }

        test("UpdateSessionContext updates fields") {
            val s = started()
            val cmd =
                UpdateSessionContext(
                    sessionId = sessionId,
                    energy = 0.8f,
                    intensity = 0.5f,
                    moodTags = listOf("chill", "ambient"),
                    attentionMode = "background",
                )
            val r = AdaptiveHandler.handle(s, cmd, ctx(s.version), deps())
            r.shouldBeInstanceOf<CommandResult.Success<AdaptiveSessionAggregate>>()
            r.value.energy shouldBe 0.8f
            r.value.intensity shouldBe 0.5f
            r.value.moodTags shouldBe listOf("chill", "ambient")
            r.value.attentionMode shouldBe "background"
            r.value.lastActivityAt shouldBe now
        }

        test("UpdateSessionContext fails when ended") {
            val s = started().copy(state = SessionState.ENDED, endedAt = now)
            val cmd = UpdateSessionContext(sessionId, 0.5f, 0.5f, emptyList(), "focus")
            val r = AdaptiveHandler.handle(s, cmd, ctx(s.version), deps())
            r.shouldBeInstanceOf<CommandResult.Failed>()
            r.failure.shouldBeInstanceOf<Failure.InvariantViolation>()
        }

        test("RewriteQueueTail replaces tail and increments queueVersion") {
            val s = started().copy(queueVersion = 0)
            val cmd =
                RewriteQueueTail(
                    sessionId = sessionId,
                    baseQueueVersion = 0,
                    keepFromPosition = 0,
                    newTail =
                        listOf(
                            NewQueueEntry(AdaptiveQueueEntryId("e1"), t1, "seed", "opener"),
                            NewQueueEntry(AdaptiveQueueEntryId("e2"), t2, "flow", "build"),
                        ),
                )
            val r = AdaptiveHandler.handle(s, cmd, ctx(s.version), deps(setOf(t1, t2)))
            r.shouldBeInstanceOf<CommandResult.Success<AdaptiveSessionAggregate>>()
            r.value.queue.size shouldBe 2
            r.value.queue[0].position shouldBe 0
            r.value.queue[1].position shouldBe 1
            r.value.queueVersion shouldBe 1
        }

        test("RewriteQueueTail preserves entries before keepFromPosition") {
            val existing =
                listOf(
                    entry("e1", t1, 0),
                    entry("e2", t2, 1),
                    entry("e3", t3, 2),
                )
            val s = withQueue(existing, queueVersion = 1)
            val cmd =
                RewriteQueueTail(
                    sessionId = sessionId,
                    baseQueueVersion = 1,
                    keepFromPosition = 2,
                    newTail =
                        listOf(
                            NewQueueEntry(AdaptiveQueueEntryId("e4"), t1, "repeat", "encore"),
                        ),
                )
            val r = AdaptiveHandler.handle(s, cmd, ctx(s.version), deps(setOf(t1)))
            r.shouldBeInstanceOf<CommandResult.Success<AdaptiveSessionAggregate>>()
            r.value.queue.size shouldBe 3
            r.value.queue[0].id shouldBe AdaptiveQueueEntryId("e1")
            r.value.queue[1].id shouldBe AdaptiveQueueEntryId("e2")
            r.value.queue[2].id shouldBe AdaptiveQueueEntryId("e4")
            r.value.queue[2].position shouldBe 2
        }

        test("RewriteQueueTail fails with stale baseQueueVersion") {
            val s = started().copy(queueVersion = 3)
            val cmd = RewriteQueueTail(sessionId, baseQueueVersion = 2, keepFromPosition = 0, newTail = emptyList())
            val r = AdaptiveHandler.handle(s, cmd, ctx(s.version), deps())
            r.shouldBeInstanceOf<CommandResult.Failed>()
            r.failure.shouldBeInstanceOf<Failure.InvariantViolation>()
        }

        test("RewriteQueueTail fails with unknown trackIds") {
            val s = started().copy(queueVersion = 0)
            val cmd =
                RewriteQueueTail(
                    sessionId = sessionId,
                    baseQueueVersion = 0,
                    keepFromPosition = 0,
                    newTail = listOf(NewQueueEntry(AdaptiveQueueEntryId("e1"), t1, null, null)),
                )
            val r = AdaptiveHandler.handle(s, cmd, ctx(s.version), deps(emptySet()))
            r.shouldBeInstanceOf<CommandResult.Failed>()
            r.failure.shouldBeInstanceOf<Failure.InvariantViolation>()
        }

        test("RewriteQueueTail fails with duplicate entry IDs") {
            val s = started().copy(queueVersion = 0)
            val cmd =
                RewriteQueueTail(
                    sessionId = sessionId,
                    baseQueueVersion = 0,
                    keepFromPosition = 0,
                    newTail =
                        listOf(
                            NewQueueEntry(AdaptiveQueueEntryId("e1"), t1, null, null),
                            NewQueueEntry(AdaptiveQueueEntryId("e1"), t2, null, null),
                        ),
                )
            val r = AdaptiveHandler.handle(s, cmd, ctx(s.version), deps(setOf(t1, t2)))
            r.shouldBeInstanceOf<CommandResult.Failed>()
            r.failure.shouldBeInstanceOf<Failure.InvariantViolation>()
        }

        test("RewriteQueueTail fails when session ended") {
            val s = started().copy(state = SessionState.ENDED, endedAt = now, queueVersion = 0)
            val cmd = RewriteQueueTail(sessionId, baseQueueVersion = 0, keepFromPosition = 0, newTail = emptyList())
            val r = AdaptiveHandler.handle(s, cmd, ctx(s.version), deps())
            r.shouldBeInstanceOf<CommandResult.Failed>()
            r.failure.shouldBeInstanceOf<Failure.InvariantViolation>()
        }

        test("currently playing track not affected by queue rewrite") {
            val playing = entry("playing", t1, 0)
            val upcoming = entry("upcoming", t2, 1)
            val s = withQueue(listOf(playing, upcoming), queueVersion = 1)
            val cmd =
                RewriteQueueTail(
                    sessionId = sessionId,
                    baseQueueVersion = 1,
                    keepFromPosition = 1,
                    newTail =
                        listOf(
                            NewQueueEntry(AdaptiveQueueEntryId("new-1"), t3, "swap", "pivot"),
                        ),
                )
            val r = AdaptiveHandler.handle(s, cmd, ctx(s.version), deps(setOf(t3)))
            r.shouldBeInstanceOf<CommandResult.Success<AdaptiveSessionAggregate>>()
            r.value.queue[0] shouldBe playing
            r.value.queue[1].id shouldBe AdaptiveQueueEntryId("new-1")
        }

        test("RewriteQueueTail rejects negative keepFromPosition") {
            val s = started().copy(queueVersion = 0)
            val cmd = RewriteQueueTail(sessionId, baseQueueVersion = 0, keepFromPosition = -1, newTail = emptyList())
            val r = AdaptiveHandler.handle(s, cmd, ctx(s.version), deps())
            r.shouldBeInstanceOf<CommandResult.Failed>()
            r.failure.shouldBeInstanceOf<Failure.InvariantViolation>()
        }

        test("RewriteQueueTail rejects keepFromPosition exceeding queue bounds") {
            val existing =
                listOf(
                    entry("e1", t1, 0),
                    entry("e2", t2, 1),
                )
            val s = withQueue(existing, queueVersion = 1)
            val cmd = RewriteQueueTail(sessionId, baseQueueVersion = 1, keepFromPosition = 5, newTail = emptyList())
            val r = AdaptiveHandler.handle(s, cmd, ctx(s.version), deps())
            r.shouldBeInstanceOf<CommandResult.Failed>()
            r.failure.shouldBeInstanceOf<Failure.InvariantViolation>()
        }

        test("version mismatch returns Conflict") {
            val s = started(AggregateVersion(5))
            val r =
                AdaptiveHandler.handle(
                    s,
                    EndListeningSession(sessionId, null),
                    ctx(AggregateVersion(3)),
                    deps(),
                )
            r.shouldBeInstanceOf<CommandResult.Failed>()
            r.failure.shouldBeInstanceOf<Failure.Conflict>()
        }

        test("OCC monotonicity newVersion equals version plus one") {
            val s = started(AggregateVersion(7))
            val cmd = UpdateSessionContext(sessionId, 0.5f, 0.5f, emptyList(), "focus")
            val r = AdaptiveHandler.handle(s, cmd, ctx(AggregateVersion(7)), deps())
            r.shouldBeInstanceOf<CommandResult.Success<AdaptiveSessionAggregate>>()
            r.newVersion shouldBe AggregateVersion(8)
            r.value.version shouldBe AggregateVersion(8)
        }

        test("RewriteQueueTail with empty newTail clears tail") {
            val existing =
                listOf(
                    entry("e1", t1, 0),
                    entry("e2", t2, 1),
                    entry("e3", t3, 2),
                )
            val s = withQueue(existing, queueVersion = 1)
            val cmd = RewriteQueueTail(sessionId, baseQueueVersion = 1, keepFromPosition = 1, newTail = emptyList())
            val r = AdaptiveHandler.handle(s, cmd, ctx(s.version), deps())
            r.shouldBeInstanceOf<CommandResult.Success<AdaptiveSessionAggregate>>()
            r.value.queue.size shouldBe 1
            r.value.queue[0].id shouldBe AdaptiveQueueEntryId("e1")
            r.value.queueVersion shouldBe 2
        }

        test("RewriteQueueTail with keepFromPosition=0 fully replaces non-empty queue") {
            val existing = listOf(entry("e1", t1, 0), entry("e2", t2, 1))
            val s = withQueue(existing, queueVersion = 1)
            val cmd =
                RewriteQueueTail(
                    sessionId,
                    baseQueueVersion = 1,
                    keepFromPosition = 0,
                    newTail = listOf(NewQueueEntry(AdaptiveQueueEntryId("e3"), t3, "replace", "swap")),
                )
            val r = AdaptiveHandler.handle(s, cmd, ctx(s.version), deps(setOf(t3)))
            r.shouldBeInstanceOf<CommandResult.Success<AdaptiveSessionAggregate>>()
            r.value.queue.size shouldBe 1
            r.value.queue[0].id shouldBe AdaptiveQueueEntryId("e3")
        }

        test("Consecutive rewrites increment queueVersion correctly") {
            var s = started().copy(queueVersion = 0)
            for (i in 1..3) {
                val cmd =
                    RewriteQueueTail(
                        sessionId,
                        baseQueueVersion = (i - 1).toLong(),
                        keepFromPosition = 0,
                        newTail = listOf(NewQueueEntry(AdaptiveQueueEntryId("e$i"), t1, "iter$i", null)),
                    )
                val r = AdaptiveHandler.handle(s, cmd, ctx(s.version), deps(setOf(t1)))
                r.shouldBeInstanceOf<CommandResult.Success<AdaptiveSessionAggregate>>()
                r.value.queueVersion shouldBe i.toLong()
                s = r.value
            }
        }
    })
