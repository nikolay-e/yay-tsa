package dev.yaytsa.testkit.scenario

import dev.yaytsa.application.playback.PlaybackUseCases
import dev.yaytsa.domain.playback.AcquireLease
import dev.yaytsa.domain.playback.DeviceId
import dev.yaytsa.domain.playback.Pause
import dev.yaytsa.domain.playback.QueueEntry
import dev.yaytsa.domain.playback.QueueEntryId
import dev.yaytsa.domain.playback.SessionId
import dev.yaytsa.domain.playback.SkipNext
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
import io.kotest.matchers.types.shouldBeInstanceOf
import java.time.Duration

class LeaseLifecycleScenarioTest :
    FunSpec({
        test("multi-device lease lifecycle: acquire, play, reject, expire, re-acquire, pause, reject") {
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

            val step1 =
                useCases.execute(
                    AcquireLease(sessionId, deviceA, leaseDuration),
                    ctx(currentVersion()),
                )
            step1.shouldBeInstanceOf<CommandResult.Success<*>>()

            val step2 =
                useCases.execute(
                    StartPlaybackWithTracks(
                        sessionId = sessionId,
                        deviceId = deviceA,
                        leaseDuration = leaseDuration,
                        entries =
                            listOf(
                                QueueEntry(QueueEntryId("e1"), TrackId("t1")),
                                QueueEntry(QueueEntryId("e2"), TrackId("t2")),
                            ),
                    ),
                    ctx(currentVersion()),
                )
            step2.shouldBeInstanceOf<CommandResult.Success<*>>()

            val step3 =
                useCases.execute(
                    Pause(sessionId, deviceB),
                    ctx(currentVersion()),
                )
            step3.shouldBeInstanceOf<CommandResult.Failed>()
            (step3 as CommandResult.Failed).failure.shouldBeInstanceOf<Failure.Unauthorized>()

            clock.advance(120)

            val step5 =
                useCases.execute(
                    AcquireLease(sessionId, deviceB, leaseDuration),
                    ctx(currentVersion()),
                )
            step5.shouldBeInstanceOf<CommandResult.Success<*>>()

            val step6 =
                useCases.execute(
                    Pause(sessionId, deviceB),
                    ctx(currentVersion()),
                )
            step6.shouldBeInstanceOf<CommandResult.Success<*>>()

            val step7 =
                useCases.execute(
                    SkipNext(sessionId, deviceA),
                    ctx(currentVersion()),
                )
            step7.shouldBeInstanceOf<CommandResult.Failed>()
            (step7 as CommandResult.Failed).failure.shouldBeInstanceOf<Failure.Unauthorized>()
        }

        test("lease at exact expiry instant is rejected") {
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

            val userId = UserId("user-boundary")
            val sessionId = SessionId("session-boundary")
            val protocol = ProtocolId("JELLYFIN")
            val deviceA = DeviceId("device-a")
            val leaseDuration = Duration.ofSeconds(60)
            var cmdCounter = 0

            fun ctx(expectedVersion: AggregateVersion) =
                CommandContext(
                    userId = userId,
                    protocolId = protocol,
                    requestTime = clock.now(),
                    idempotencyKey = IdempotencyKey("boundary-${++cmdCounter}"),
                    expectedVersion = expectedVersion,
                )

            fun currentVersion() = sessionRepo.find(userId, sessionId)?.version ?: AggregateVersion.INITIAL

            // Acquire lease and start playback
            val acquire =
                useCases.execute(
                    AcquireLease(sessionId, deviceA, leaseDuration),
                    ctx(currentVersion()),
                )
            acquire.shouldBeInstanceOf<CommandResult.Success<*>>()

            val startPlay =
                useCases.execute(
                    StartPlaybackWithTracks(
                        sessionId = sessionId,
                        deviceId = deviceA,
                        leaseDuration = leaseDuration,
                        entries =
                            listOf(
                                QueueEntry(QueueEntryId("be1"), TrackId("t1")),
                                QueueEntry(QueueEntryId("be2"), TrackId("t2")),
                            ),
                    ),
                    ctx(currentVersion()),
                )
            startPlay.shouldBeInstanceOf<CommandResult.Success<*>>()

            // Advance clock to EXACTLY the lease expiry instant (60 seconds)
            clock.advance(60)

            // Command at exact expiry should fail with Unauthorized
            val pauseAtExpiry =
                useCases.execute(
                    Pause(sessionId, deviceA),
                    ctx(currentVersion()),
                )
            pauseAtExpiry.shouldBeInstanceOf<CommandResult.Failed>()
            (pauseAtExpiry as CommandResult.Failed).failure.shouldBeInstanceOf<Failure.Unauthorized>()
        }
    })
