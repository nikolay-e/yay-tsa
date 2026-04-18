package dev.yaytsa.testkit.scenario

import dev.yaytsa.application.playback.PlaybackUseCases
import dev.yaytsa.domain.playback.AcquireLease
import dev.yaytsa.domain.playback.DeviceId
import dev.yaytsa.domain.playback.QueueEntry
import dev.yaytsa.domain.playback.QueueEntryId
import dev.yaytsa.domain.playback.SessionId
import dev.yaytsa.domain.playback.StartPlaybackWithTracks
import dev.yaytsa.shared.AggregateVersion
import dev.yaytsa.shared.CommandContext
import dev.yaytsa.shared.CommandResult
import dev.yaytsa.shared.Failure
import dev.yaytsa.shared.IdempotencyKey
import dev.yaytsa.shared.ProtocolId
import dev.yaytsa.shared.TrackId
import dev.yaytsa.shared.UserId
import dev.yaytsa.testkit.DirectTransactionalExecutor
import dev.yaytsa.testkit.FixedClock
import dev.yaytsa.testkit.InMemoryIdempotencyStore
import dev.yaytsa.testkit.InMemoryPlaybackSessionRepository
import dev.yaytsa.testkit.RecordingOutbox
import dev.yaytsa.testkit.testCapabilitiesRegistry
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.time.Duration

class StartPlaybackAtomicityTest :
    FunSpec({
        test("lease held by another device rejects entire StartPlaybackWithTracks") {
            val clock = FixedClock()
            val sessionRepo = InMemoryPlaybackSessionRepository()
            val knownTracks = setOf(TrackId("t1"), TrackId("t2"))
            val useCases =
                PlaybackUseCases(
                    sessionRepo = sessionRepo,
                    idempotencyStore = InMemoryIdempotencyStore(),
                    capabilities = testCapabilitiesRegistry(),
                    txExecutor = DirectTransactionalExecutor(),
                    outbox = RecordingOutbox(),
                    trackValidator = { ids -> ids.filter { it in knownTracks }.toSet() },
                    trackDurationLoader = { null },
                )

            val userId = UserId("user-1")
            val sessionId = SessionId("session-1")
            val protocol = ProtocolId("JELLYFIN")
            val deviceA = DeviceId("device-a")
            val deviceB = DeviceId("device-b")
            val leaseDuration = Duration.ofSeconds(60)
            var cmdCounter = 0

            fun ctx(expectedVersion: AggregateVersion) =
                CommandContext(
                    userId = userId,
                    protocolId = protocol,
                    requestTime = clock.now(),
                    idempotencyKey = IdempotencyKey("idem-${++cmdCounter}"),
                    expectedVersion = expectedVersion,
                )

            fun currentVersion() = sessionRepo.find(userId, sessionId)?.version ?: AggregateVersion.INITIAL

            val acquireResult =
                useCases.execute(
                    AcquireLease(sessionId, deviceA, leaseDuration),
                    ctx(currentVersion()),
                )
            acquireResult.shouldBeInstanceOf<CommandResult.Success<*>>()

            val versionAfterAcquire = currentVersion()

            val startResult =
                useCases.execute(
                    StartPlaybackWithTracks(
                        sessionId = sessionId,
                        deviceId = deviceB,
                        leaseDuration = leaseDuration,
                        entries =
                            listOf(
                                QueueEntry(QueueEntryId("e1"), TrackId("t1")),
                                QueueEntry(QueueEntryId("e2"), TrackId("t2")),
                            ),
                    ),
                    ctx(currentVersion()),
                )
            startResult.shouldBeInstanceOf<CommandResult.Failed>()
            (startResult as CommandResult.Failed).failure.shouldBeInstanceOf<Failure.Unauthorized>()

            val state = useCases.getPlaybackState(userId, sessionId)!!
            state.queue shouldBe emptyList()
            state.lease!!.owner shouldBe deviceA
            state.version shouldBe versionAfterAcquire
        }

        test("invalid track in entries rejects entire StartPlaybackWithTracks with no state change") {
            val clock = FixedClock()
            val sessionRepo = InMemoryPlaybackSessionRepository()
            val useCases =
                PlaybackUseCases(
                    sessionRepo = sessionRepo,
                    idempotencyStore = InMemoryIdempotencyStore(),
                    capabilities = testCapabilitiesRegistry(),
                    txExecutor = DirectTransactionalExecutor(),
                    outbox = RecordingOutbox(),
                    trackValidator = { ids -> ids.filter { it == TrackId("t1") }.toSet() },
                    trackDurationLoader = { null },
                )

            val userId = UserId("user-1")
            val sessionId = SessionId("session-1")
            val protocol = ProtocolId("JELLYFIN")
            val deviceId = DeviceId("device-1")
            val leaseDuration = Duration.ofSeconds(60)
            var cmdCounter = 0

            fun ctx(expectedVersion: AggregateVersion) =
                CommandContext(
                    userId = userId,
                    protocolId = protocol,
                    requestTime = clock.now(),
                    idempotencyKey = IdempotencyKey("idem-${++cmdCounter}"),
                    expectedVersion = expectedVersion,
                )

            fun currentVersion() = sessionRepo.find(userId, sessionId)?.version ?: AggregateVersion.INITIAL

            val startResult =
                useCases.execute(
                    StartPlaybackWithTracks(
                        sessionId = sessionId,
                        deviceId = deviceId,
                        leaseDuration = leaseDuration,
                        entries =
                            listOf(
                                QueueEntry(QueueEntryId("e1"), TrackId("t1")),
                                QueueEntry(QueueEntryId("e2"), TrackId("t2")),
                            ),
                    ),
                    ctx(currentVersion()),
                )
            startResult.shouldBeInstanceOf<CommandResult.Failed>()
            (startResult as CommandResult.Failed).failure.shouldBeInstanceOf<Failure.InvariantViolation>()

            val state = useCases.getPlaybackState(userId, sessionId)
            state.shouldBeNull()
        }
    })
