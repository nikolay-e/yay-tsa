package dev.yaytsa.testkit.scenario

import dev.yaytsa.application.playback.PlaybackUseCases
import dev.yaytsa.domain.playback.AcquireLease
import dev.yaytsa.domain.playback.Pause
import dev.yaytsa.domain.playback.PlaybackState
import dev.yaytsa.domain.playback.QueueEntry
import dev.yaytsa.domain.playback.QueueEntryId
import dev.yaytsa.domain.playback.Seek
import dev.yaytsa.domain.playback.SessionId
import dev.yaytsa.domain.playback.StartPlaybackWithTracks
import dev.yaytsa.domain.playback.TransferLease
import dev.yaytsa.shared.AggregateVersion
import dev.yaytsa.shared.CommandContext
import dev.yaytsa.shared.CommandResult
import dev.yaytsa.shared.DeviceId
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
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.time.Duration

class LeaseTransferScenarioTest :
    FunSpec({
        test("lease transfers from holder to target device, resuming at same queue entry and position") {
            val clock = FixedClock()
            val sessionRepo = InMemoryPlaybackSessionRepository()
            val knownTracks = setOf(TrackId("t1"), TrackId("t2"))
            val trackDuration = Duration.ofSeconds(300)
            val useCases =
                PlaybackUseCases(
                    sessionRepo = sessionRepo,
                    idempotencyStore = InMemoryIdempotencyStore(),
                    capabilities = testCapabilitiesRegistry(),
                    txExecutor = DirectTransactionalExecutor(),
                    outbox = RecordingOutbox(),
                    trackValidator = { ids -> ids.filter { it in knownTracks }.toSet() },
                    trackDurationLoader = { trackDuration },
                )

            val userId = UserId("user-1")
            val sessionId = SessionId("session-1")
            val protocol = ProtocolId("JELLYFIN")
            val deviceA = DeviceId("device-a")
            val deviceB = DeviceId("device-b")
            val deviceThree = DeviceId("device-c")
            val leaseDuration = Duration.ofSeconds(60)
            val seekPosition = Duration.ofSeconds(42)
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

            useCases
                .execute(AcquireLease(sessionId, deviceA, leaseDuration), ctx(currentVersion()))
                .shouldBeInstanceOf<CommandResult.Success<*>>()

            useCases
                .execute(
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
                ).shouldBeInstanceOf<CommandResult.Success<*>>()

            useCases
                .execute(Seek(sessionId, deviceA, seekPosition), ctx(currentVersion()))
                .shouldBeInstanceOf<CommandResult.Success<*>>()

            useCases
                .execute(Pause(sessionId, deviceA), ctx(currentVersion()))
                .shouldBeInstanceOf<CommandResult.Success<*>>()

            val nonHolderResult =
                useCases.execute(
                    TransferLease(sessionId, fromDeviceId = deviceThree, toDeviceId = deviceB, leaseDuration = leaseDuration),
                    ctx(currentVersion()),
                )
            nonHolderResult.shouldBeInstanceOf<CommandResult.Failed>()
            (nonHolderResult as CommandResult.Failed).failure.shouldBeInstanceOf<Failure.Unauthorized>()

            sessionRepo.find(userId, sessionId)!!.lease!!.owner shouldBe deviceA

            val transfer =
                useCases.execute(
                    TransferLease(sessionId, fromDeviceId = deviceA, toDeviceId = deviceB, leaseDuration = leaseDuration),
                    ctx(currentVersion()),
                )
            transfer.shouldBeInstanceOf<CommandResult.Success<*>>()

            val afterTransfer = sessionRepo.find(userId, sessionId)!!
            afterTransfer.lease!!.owner shouldBe deviceB
            afterTransfer.currentEntryId shouldBe QueueEntryId("e1")
            afterTransfer.queue.map { it.id } shouldBe listOf(QueueEntryId("e1"), QueueEntryId("e2"))
            afterTransfer.computePosition(clock.now()) shouldBe seekPosition
            afterTransfer.playbackState shouldBe PlaybackState.PAUSED

            useCases
                .execute(Pause(sessionId, deviceA), ctx(currentVersion()))
                .let { it as CommandResult.Failed }
                .failure
                .shouldBeInstanceOf<Failure.Unauthorized>()

            useCases
                .execute(Seek(sessionId, deviceB, Duration.ofSeconds(10)), ctx(currentVersion()))
                .shouldBeInstanceOf<CommandResult.Success<*>>()
        }

        test("transfer is rejected once the lease has expired") {
            val clock = FixedClock()
            val sessionRepo = InMemoryPlaybackSessionRepository()
            val useCases =
                PlaybackUseCases(
                    sessionRepo = sessionRepo,
                    idempotencyStore = InMemoryIdempotencyStore(),
                    capabilities = testCapabilitiesRegistry(),
                    txExecutor = DirectTransactionalExecutor(),
                    outbox = RecordingOutbox(),
                    trackValidator = { it },
                    trackDurationLoader = { null },
                )

            val userId = UserId("user-1")
            val sessionId = SessionId("session-1")
            val deviceA = DeviceId("device-a")
            val deviceB = DeviceId("device-b")
            val leaseDuration = Duration.ofSeconds(60)
            var cmdCounter = 0

            fun ctx(expectedVersion: AggregateVersion) =
                CommandContext(
                    userId = userId,
                    protocolId = ProtocolId("JELLYFIN"),
                    requestTime = clock.now(),
                    idempotencyKey = IdempotencyKey("idem-${++cmdCounter}"),
                    expectedVersion = expectedVersion,
                )

            fun currentVersion() = sessionRepo.find(userId, sessionId)?.version ?: AggregateVersion.INITIAL

            useCases
                .execute(AcquireLease(sessionId, deviceA, leaseDuration), ctx(currentVersion()))
                .shouldBeInstanceOf<CommandResult.Success<*>>()

            clock.advance(120)

            val expired =
                useCases.execute(
                    TransferLease(sessionId, fromDeviceId = deviceA, toDeviceId = deviceB, leaseDuration = leaseDuration),
                    ctx(currentVersion()),
                )
            expired.shouldBeInstanceOf<CommandResult.Failed>()
            (expired as CommandResult.Failed).failure.shouldBeInstanceOf<Failure.Unauthorized>()
        }
    })
