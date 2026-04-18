package dev.yaytsa.testkit.scenario

import dev.yaytsa.application.playback.PlaybackUseCases
import dev.yaytsa.domain.playback.DeviceId
import dev.yaytsa.domain.playback.Pause
import dev.yaytsa.domain.playback.Play
import dev.yaytsa.domain.playback.QueueEntry
import dev.yaytsa.domain.playback.QueueEntryId
import dev.yaytsa.domain.playback.Seek
import dev.yaytsa.domain.playback.SessionId
import dev.yaytsa.domain.playback.StartPlaybackWithTracks
import dev.yaytsa.shared.AggregateVersion
import dev.yaytsa.shared.CommandContext
import dev.yaytsa.shared.CommandResult
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
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.time.Duration

class PositionTrackingScenarioTest :
    FunSpec({
        test("lazy position computation through play, pause, resume, seek") {
            val clock = FixedClock()
            val sessionRepo = InMemoryPlaybackSessionRepository()
            val knownTracks = setOf(TrackId("t1"))
            val useCases =
                PlaybackUseCases(
                    sessionRepo = sessionRepo,
                    idempotencyStore = InMemoryIdempotencyStore(),
                    capabilities = testCapabilitiesRegistry(),
                    txExecutor = DirectTransactionalExecutor(),
                    outbox = RecordingOutbox(),
                    trackValidator = { ids -> ids.filter { it in knownTracks }.toSet() },
                    trackDurationLoader = { Duration.ofMinutes(5) },
                )

            val userId = UserId("user-1")
            val sessionId = SessionId("session-1")
            val protocol = ProtocolId("JELLYFIN")
            val deviceId = DeviceId("device-1")
            val leaseDuration = Duration.ofMinutes(30)
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

            fun readState() = useCases.getPlaybackState(userId, sessionId)!!

            val step1 =
                useCases.execute(
                    StartPlaybackWithTracks(
                        sessionId = sessionId,
                        deviceId = deviceId,
                        leaseDuration = leaseDuration,
                        entries = listOf(QueueEntry(QueueEntryId("e1"), TrackId("t1"))),
                    ),
                    ctx(currentVersion()),
                )
            step1.shouldBeInstanceOf<CommandResult.Success<*>>()

            clock.advance(30)
            readState().computePosition(clock.now()) shouldBe Duration.ofSeconds(30)

            val step3 =
                useCases.execute(
                    Pause(sessionId, deviceId),
                    ctx(currentVersion()),
                )
            step3.shouldBeInstanceOf<CommandResult.Success<*>>()
            readState().lastKnownPosition shouldBe Duration.ofSeconds(30)

            clock.advance(60)
            readState().computePosition(clock.now()) shouldBe Duration.ofSeconds(30)

            val step5 =
                useCases.execute(
                    Play(sessionId, deviceId),
                    ctx(currentVersion()),
                )
            step5.shouldBeInstanceOf<CommandResult.Success<*>>()

            clock.advance(10)
            readState().computePosition(clock.now()) shouldBe Duration.ofSeconds(40)

            val step7 =
                useCases.execute(
                    Seek(sessionId, deviceId, Duration.ofSeconds(5)),
                    ctx(currentVersion()),
                )
            step7.shouldBeInstanceOf<CommandResult.Success<*>>()

            readState().computePosition(clock.now()) shouldBe Duration.ofSeconds(5)
        }
    })
