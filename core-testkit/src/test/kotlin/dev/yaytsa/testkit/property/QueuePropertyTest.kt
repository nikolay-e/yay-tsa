package dev.yaytsa.testkit.property

import dev.yaytsa.domain.playback.AcquireLease
import dev.yaytsa.domain.playback.AddToQueue
import dev.yaytsa.domain.playback.ClearQueue
import dev.yaytsa.domain.playback.DeviceId
import dev.yaytsa.domain.playback.MoveQueueEntry
import dev.yaytsa.domain.playback.Pause
import dev.yaytsa.domain.playback.Play
import dev.yaytsa.domain.playback.PlaybackCommand
import dev.yaytsa.domain.playback.PlaybackDeps
import dev.yaytsa.domain.playback.PlaybackHandler
import dev.yaytsa.domain.playback.PlaybackSessionAggregate
import dev.yaytsa.domain.playback.PlaybackState
import dev.yaytsa.domain.playback.QueueEntry
import dev.yaytsa.domain.playback.QueueEntryId
import dev.yaytsa.domain.playback.RemoveFromQueue
import dev.yaytsa.domain.playback.ReplaceQueue
import dev.yaytsa.domain.playback.SessionId
import dev.yaytsa.domain.playback.SkipNext
import dev.yaytsa.domain.playback.SkipPrevious
import dev.yaytsa.shared.AggregateVersion
import dev.yaytsa.shared.CommandContext
import dev.yaytsa.shared.CommandResult
import dev.yaytsa.shared.IdempotencyKey
import dev.yaytsa.shared.ProtocolId
import dev.yaytsa.shared.TrackId
import dev.yaytsa.shared.UserId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.map
import io.kotest.property.checkAll
import java.time.Duration
import java.time.Instant

class QueuePropertyTest :
    FunSpec({
        val userId = UserId("u-prop")
        val sessionId = SessionId("s-prop")
        val deviceId = DeviceId("d-prop")
        val now = Instant.parse("2025-06-01T12:00:00Z")
        val leaseDuration = Duration.ofHours(1)

        val trackPool = (0..49).map { TrackId("t-$it") }
        val knownTrackIds = trackPool.toSet()
        val deps = PlaybackDeps(knownTrackIds = knownTrackIds, currentTrackDuration = null)

        fun ctx(
            version: AggregateVersion,
            keyIdx: Int,
        ) = CommandContext(
            userId = userId,
            protocolId = ProtocolId("JELLYFIN"),
            requestTime = now,
            idempotencyKey = IdempotencyKey("k-$keyIdx"),
            expectedVersion = version,
        )

        fun acquireLeaseOnFresh(): PlaybackSessionAggregate {
            val empty = PlaybackSessionAggregate.empty(userId, sessionId, now)
            val cmd = AcquireLease(sessionId, deviceId, leaseDuration)
            val result = PlaybackHandler.handle(empty, cmd, ctx(AggregateVersion.INITIAL, 0), deps)
            return (result as CommandResult.Success).value
        }

        val arbEntryIndex = Arb.int(0..49)
        val arbTrackIndex = Arb.int(0..49)

        val arbOpCode = Arb.int(0..8)
        val arbOpsSequence = Arb.list(arbOpCode, 1..30)

        test("queue never contains duplicate entryIds after any sequence of queue operations") {
            checkAll(
                150,
                arbOpsSequence,
                Arb.list(arbEntryIndex, 30..30),
                Arb.list(arbTrackIndex, 30..30),
                Arb.list(Arb.int(0..20), 30..30),
            ) { ops, entryIndices, trackIndices, moveIndices ->
                var session = acquireLeaseOnFresh()
                var nextEntryCounter = 0
                val usedEntryIds = mutableListOf<String>()
                var keyCounter = 100

                for ((i, opCode) in ops.withIndex()) {
                    val version = session.version
                    val cmdCtx = ctx(version, keyCounter++)

                    val cmd: PlaybackCommand =
                        when (opCode) {
                            0 -> {
                                val count = (entryIndices.getOrElse(i) { 0 } % 5) + 1
                                val entries =
                                    (0 until count).map { j ->
                                        val eId = "e-${nextEntryCounter++}"
                                        usedEntryIds.add(eId)
                                        QueueEntry(QueueEntryId(eId), trackPool[trackIndices.getOrElse(i + j) { 0 } % trackPool.size])
                                    }
                                AddToQueue(sessionId, deviceId, entries)
                            }
                            1 -> {
                                val queueIds = session.queue.map { it.id }
                                if (queueIds.isEmpty()) continue
                                val target = queueIds[entryIndices.getOrElse(i) { 0 } % queueIds.size]
                                RemoveFromQueue(sessionId, deviceId, target)
                            }
                            2 -> {
                                val queueIds = session.queue.map { it.id }
                                if (queueIds.isEmpty()) continue
                                val target = queueIds[entryIndices.getOrElse(i) { 0 } % queueIds.size]
                                val newIdx = moveIndices.getOrElse(i) { 0 } % session.queue.size
                                MoveQueueEntry(sessionId, deviceId, target, newIdx)
                            }
                            3 -> {
                                val count = (entryIndices.getOrElse(i) { 0 } % 5) + 1
                                val entries =
                                    (0 until count).map { j ->
                                        val eId = "e-${nextEntryCounter++}"
                                        usedEntryIds.add(eId)
                                        QueueEntry(QueueEntryId(eId), trackPool[trackIndices.getOrElse(i + j) { 0 } % trackPool.size])
                                    }
                                ReplaceQueue(sessionId, deviceId, entries)
                            }
                            4 -> {
                                if (session.queue.isEmpty()) continue
                                Play(sessionId, deviceId)
                            }
                            5 -> {
                                if (session.queue.isEmpty() || session.currentEntryId == null) continue
                                SkipNext(sessionId, deviceId)
                            }
                            6 -> {
                                if (session.queue.isEmpty() || session.currentEntryId == null) continue
                                SkipPrevious(sessionId, deviceId)
                            }
                            7 -> {
                                if (session.playbackState != PlaybackState.PLAYING) continue
                                Pause(sessionId, deviceId)
                            }
                            8 -> {
                                ClearQueue(sessionId, deviceId)
                            }
                            else -> continue
                        }

                    when (val result = PlaybackHandler.handle(session, cmd, cmdCtx, deps)) {
                        is CommandResult.Success -> session = result.value
                        is CommandResult.Failed -> {}
                    }

                    val entryIds = session.queue.map { it.id }
                    entryIds shouldHaveSize entryIds.toSet().size

                    val currentId = session.currentEntryId
                    if (currentId != null) {
                        session.queue.any { it.id == currentId } shouldBe true
                    }

                    // PLAYING implies currentEntryId is not null
                    if (session.playbackState == PlaybackState.PLAYING) {
                        session.currentEntryId shouldNotBe null
                    }
                }
            }
        }

        test("currentEntryId is always null or present in queue after any queue mutation") {
            checkAll(200, Arb.list(Arb.int(0..8), 1..20), Arb.list(Arb.int(0..49), 20..20)) { ops, indices ->
                var session = acquireLeaseOnFresh()
                var nextEntry = 0
                var keyCounter = 200

                for ((i, opCode) in ops.withIndex()) {
                    val version = session.version
                    val cmdCtx = ctx(version, keyCounter++)

                    val cmd: PlaybackCommand =
                        when (opCode) {
                            0 -> {
                                val eId = "ce-${nextEntry++}"
                                AddToQueue(sessionId, deviceId, listOf(QueueEntry(QueueEntryId(eId), trackPool[indices.getOrElse(i) { 0 } % trackPool.size])))
                            }
                            1 -> {
                                if (session.queue.isEmpty()) continue
                                val target = session.queue[indices.getOrElse(i) { 0 } % session.queue.size].id
                                RemoveFromQueue(sessionId, deviceId, target)
                            }
                            2 -> {
                                if (session.queue.isEmpty()) continue
                                val target = session.queue[indices.getOrElse(i) { 0 } % session.queue.size].id
                                MoveQueueEntry(sessionId, deviceId, target, 0)
                            }
                            3 -> {
                                val eId = "ce-${nextEntry++}"
                                ReplaceQueue(sessionId, deviceId, listOf(QueueEntry(QueueEntryId(eId), trackPool[0])))
                            }
                            4 -> {
                                if (session.queue.isEmpty()) continue
                                Play(sessionId, deviceId)
                            }
                            5 -> {
                                if (session.queue.isEmpty() || session.currentEntryId == null) continue
                                SkipNext(sessionId, deviceId)
                            }
                            6 -> {
                                if (session.queue.isEmpty() || session.currentEntryId == null) continue
                                SkipPrevious(sessionId, deviceId)
                            }
                            7 -> {
                                if (session.playbackState != PlaybackState.PLAYING) continue
                                Pause(sessionId, deviceId)
                            }
                            8 -> {
                                ClearQueue(sessionId, deviceId)
                            }
                            else -> continue
                        }

                    when (val result = PlaybackHandler.handle(session, cmd, cmdCtx, deps)) {
                        is CommandResult.Success -> session = result.value
                        is CommandResult.Failed -> {}
                    }

                    val currentId = session.currentEntryId
                    if (currentId != null) {
                        session.queue.any { it.id == currentId } shouldBe true
                    }
                }
            }
        }
    })
