package dev.yaytsa.testkit.usecase

import dev.yaytsa.application.playback.PlaybackUseCases
import dev.yaytsa.application.shared.ProtocolCapabilitiesRegistry
import dev.yaytsa.domain.playback.AcquireLease
import dev.yaytsa.domain.playback.AddToQueue
import dev.yaytsa.domain.playback.DeviceId
import dev.yaytsa.domain.playback.QueueEntry
import dev.yaytsa.domain.playback.QueueEntryId
import dev.yaytsa.domain.playback.Seek
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
import dev.yaytsa.testkit.InMemoryIdempotencyStore
import dev.yaytsa.testkit.InMemoryPlaybackSessionRepository
import dev.yaytsa.testkit.RecordingOutbox
import dev.yaytsa.testkit.testCapabilitiesRegistry
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.time.Duration
import java.time.Instant

class PlaybackUseCasesTest :
    FunSpec({
        val userId = UserId("user-1")
        val sessionId = SessionId("session-1")
        val deviceId = DeviceId("device-1")
        val now = Instant.parse("2025-01-01T12:00:00Z")
        val trackId = TrackId("track-1")
        val knownTracks = setOf(trackId)
        val trackDuration = Duration.ofMinutes(4)

        fun buildUseCases(
            sessionRepo: InMemoryPlaybackSessionRepository = InMemoryPlaybackSessionRepository(),
            idempotencyStore: InMemoryIdempotencyStore = InMemoryIdempotencyStore(),
            capabilities: ProtocolCapabilitiesRegistry = testCapabilitiesRegistry(),
            outbox: RecordingOutbox = RecordingOutbox(),
            trackValidator: (Set<TrackId>) -> Set<TrackId> = { ids -> ids.intersect(knownTracks) },
            trackDurationLoader: (TrackId) -> Duration? = { trackDuration },
        ) = Triple(
            PlaybackUseCases(sessionRepo, idempotencyStore, capabilities, DirectTransactionalExecutor(), outbox, trackValidator, trackDurationLoader),
            outbox,
            idempotencyStore,
        )

        fun ctx(
            key: String = "key-1",
            protocol: ProtocolId = ProtocolId("JELLYFIN"),
            version: AggregateVersion = AggregateVersion.INITIAL,
        ) = CommandContext(
            userId = userId,
            protocolId = protocol,
            requestTime = now,
            idempotencyKey = IdempotencyKey(key),
            expectedVersion = version,
        )

        fun acquireLeaseCmd() = AcquireLease(sessionId, deviceId, Duration.ofMinutes(30))

        fun startPlaybackCmd() =
            StartPlaybackWithTracks(
                sessionId,
                deviceId,
                Duration.ofMinutes(30),
                listOf(QueueEntry(QueueEntryId("e1"), trackId)),
            )

        test("idempotent replay with same key and payload returns same result without duplicating outbox") {
            val (useCases, outbox, _) = buildUseCases()
            val cmd = acquireLeaseCmd()

            val first = useCases.execute(cmd, ctx())
            first.shouldBeInstanceOf<CommandResult.Success<*>>()
            outbox.notifications.size shouldBe 1

            val second = useCases.execute(cmd, ctx(version = AggregateVersion(1)))
            second.shouldBeInstanceOf<CommandResult.Success<*>>()
            outbox.notifications.size shouldBe 1
        }

        test("idempotent replay with same key but different payload returns InvariantViolation") {
            val (useCases, _, _) = buildUseCases()

            useCases
                .execute(acquireLeaseCmd(), ctx())
                .shouldBeInstanceOf<CommandResult.Success<*>>()

            val differentCmd = AcquireLease(sessionId, deviceId, Duration.ofMinutes(60))
            val result = useCases.execute(differentCmd, ctx(version = AggregateVersion(1)))
            result.shouldBeInstanceOf<CommandResult.Failed>()
            result.failure.shouldBeInstanceOf<Failure.InvariantViolation>()
        }

        test("same idempotency key with different command types both succeed") {
            val (useCases, _, _) = buildUseCases()

            val leaseResult = useCases.execute(acquireLeaseCmd(), ctx(key = "shared-key"))
            leaseResult.shouldBeInstanceOf<CommandResult.Success<*>>()

            val addCmd =
                AddToQueue(
                    sessionId,
                    deviceId,
                    listOf(QueueEntry(QueueEntryId("e1"), trackId)),
                )
            val addResult = useCases.execute(addCmd, ctx(key = "shared-key", version = AggregateVersion(1)))
            addResult.shouldBeInstanceOf<CommandResult.Success<*>>()
        }

        test("successful command enqueues exactly one outbox notification") {
            val (useCases, outbox, _) = buildUseCases()

            useCases
                .execute(acquireLeaseCmd(), ctx())
                .shouldBeInstanceOf<CommandResult.Success<*>>()

            outbox.notifications.size shouldBe 1
        }

        test("failed command leaves outbox empty") {
            val (useCases, outbox, _) = buildUseCases()

            val cmd =
                AddToQueue(
                    sessionId,
                    deviceId,
                    listOf(QueueEntry(QueueEntryId("e1"), TrackId("unknown-track"))),
                )
            val result = useCases.execute(cmd, ctx())
            result.shouldBeInstanceOf<CommandResult.Failed>()
            outbox.notifications.size shouldBe 0
        }

        test("idempotent replay does not add second outbox notification") {
            val (useCases, outbox, _) = buildUseCases()
            val cmd = acquireLeaseCmd()

            useCases.execute(cmd, ctx())
            outbox.notifications.size shouldBe 1

            useCases.execute(cmd, ctx(version = AggregateVersion(1)))
            outbox.notifications.size shouldBe 1
        }

        test("command via unsupported protocol returns UnsupportedByProtocol") {
            val (useCases, _, _) = buildUseCases()

            val result = useCases.execute(acquireLeaseCmd(), ctx(protocol = ProtocolId("SUBSONIC")))
            result.shouldBeInstanceOf<CommandResult.Failed>()
            result.failure.shouldBeInstanceOf<Failure.UnsupportedByProtocol>()
        }

        test("same command via JELLYFIN succeeds") {
            val (useCases, _, _) = buildUseCases()

            val result = useCases.execute(acquireLeaseCmd(), ctx(protocol = ProtocolId("JELLYFIN")))
            result.shouldBeInstanceOf<CommandResult.Success<*>>()
        }

        test("AddToQueue with unknown track returns InvariantViolation") {
            val (useCases, _, _) = buildUseCases()

            useCases
                .execute(acquireLeaseCmd(), ctx(key = "lease"))
                .shouldBeInstanceOf<CommandResult.Success<*>>()

            val cmd =
                AddToQueue(
                    sessionId,
                    deviceId,
                    listOf(QueueEntry(QueueEntryId("e1"), TrackId("not-in-library"))),
                )
            val result = useCases.execute(cmd, ctx(key = "add", version = AggregateVersion(1)))
            result.shouldBeInstanceOf<CommandResult.Failed>()
            result.failure.shouldBeInstanceOf<Failure.InvariantViolation>()
        }

        test("Seek with position past track duration returns InvariantViolation") {
            val (useCases, _, _) = buildUseCases()

            useCases
                .execute(startPlaybackCmd(), ctx(key = "start"))
                .shouldBeInstanceOf<CommandResult.Success<*>>()

            val seekCmd = Seek(sessionId, deviceId, Duration.ofMinutes(10))
            val result = useCases.execute(seekCmd, ctx(key = "seek", version = AggregateVersion(1)))
            result.shouldBeInstanceOf<CommandResult.Failed>()
            result.failure.shouldBeInstanceOf<Failure.InvariantViolation>()
        }
    })
