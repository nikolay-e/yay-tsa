package dev.yaytsa.testkit.usecase

import dev.yaytsa.application.playback.PlaybackUseCases
import dev.yaytsa.application.shared.port.TransactionalCommandExecutor
import dev.yaytsa.domain.playback.AcquireLease
import dev.yaytsa.domain.playback.DeviceId
import dev.yaytsa.domain.playback.PlaybackSessionAggregate
import dev.yaytsa.domain.playback.RefreshLease
import dev.yaytsa.domain.playback.SessionId
import dev.yaytsa.shared.AggregateVersion
import dev.yaytsa.shared.CommandContext
import dev.yaytsa.shared.CommandResult
import dev.yaytsa.shared.Failure
import dev.yaytsa.shared.IdempotencyKey
import dev.yaytsa.shared.ProtocolId
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

class OccThroughUseCaseTest :
    FunSpec({
        val userId = UserId("u-occ")
        val sessionId = SessionId("s-occ")
        val deviceId = DeviceId("d-occ")
        val now = Instant.parse("2025-06-01T12:00:00Z")

        fun ctx(version: AggregateVersion = AggregateVersion.INITIAL) =
            CommandContext(
                userId = userId,
                protocolId = ProtocolId("JELLYFIN"),
                requestTime = now,
                idempotencyKey = IdempotencyKey("occ-key"),
                expectedVersion = version,
            )

        class OccSimulatingExecutor : TransactionalCommandExecutor {
            var shouldThrowOcc = false

            override fun <T> execute(block: () -> CommandResult<T>): CommandResult<T> {
                if (shouldThrowOcc) {
                    return CommandResult.Failed(Failure.StorageConflict("PlaybackSession", "concurrent modification"))
                }
                return block()
            }
        }

        fun buildUseCases(executor: OccSimulatingExecutor) =
            PlaybackUseCases(
                sessionRepo = InMemoryPlaybackSessionRepository(),
                idempotencyStore = InMemoryIdempotencyStore(),
                capabilities = testCapabilitiesRegistry(),
                txExecutor = executor,
                outbox = RecordingOutbox(),
                trackValidator = { ids -> ids },
                trackDurationLoader = { Duration.ofMinutes(4) },
            )

        test("first call succeeds when OCC is not triggered") {
            val executor = OccSimulatingExecutor()
            val useCases = buildUseCases(executor)

            val cmd = AcquireLease(sessionId, deviceId, Duration.ofMinutes(30))
            val result = useCases.execute(cmd, ctx())

            result.shouldBeInstanceOf<CommandResult.Success<*>>()
        }

        test("StorageConflict is returned when executor simulates OCC") {
            val executor = OccSimulatingExecutor()
            val useCases = buildUseCases(executor)

            executor.shouldThrowOcc = true
            val cmd = AcquireLease(sessionId, deviceId, Duration.ofMinutes(30))
            val result = useCases.execute(cmd, ctx())

            result.shouldBeInstanceOf<CommandResult.Failed>()
            result.failure.shouldBeInstanceOf<Failure.StorageConflict>()
        }

        test("success then StorageConflict on second call simulates concurrent modification") {
            val executor = OccSimulatingExecutor()
            val useCases = buildUseCases(executor)

            val cmd = AcquireLease(sessionId, deviceId, Duration.ofMinutes(30))
            val first = useCases.execute(cmd, ctx())
            first.shouldBeInstanceOf<CommandResult.Success<*>>()

            executor.shouldThrowOcc = true
            val second = useCases.execute(cmd, ctx(version = AggregateVersion(1)))
            second.shouldBeInstanceOf<CommandResult.Failed>()
            (second.failure is Failure.StorageConflict) shouldBe true
        }

        class AlwaysThrowingExecutor : TransactionalCommandExecutor {
            override fun <T> execute(block: () -> CommandResult<T>): CommandResult<T> =
                CommandResult.Failed(Failure.StorageConflict("PlaybackSession", "always conflict"))
        }

        test("executor that always returns StorageConflict produces Failed result") {
            val useCases =
                PlaybackUseCases(
                    sessionRepo = InMemoryPlaybackSessionRepository(),
                    idempotencyStore = InMemoryIdempotencyStore(),
                    capabilities = testCapabilitiesRegistry(),
                    txExecutor = AlwaysThrowingExecutor(),
                    outbox = RecordingOutbox(),
                    trackValidator = { ids -> ids },
                    trackDurationLoader = { Duration.ofMinutes(4) },
                )

            val cmd = AcquireLease(sessionId, deviceId, Duration.ofMinutes(30))
            val result = useCases.execute(cmd, ctx())

            result.shouldBeInstanceOf<CommandResult.Failed>()
            val failure = result.failure
            failure.shouldBeInstanceOf<Failure.StorageConflict>()
            failure.aggregateType shouldBe "PlaybackSession"
        }

        test("version mismatch through real handler produces Conflict") {
            val sessionRepo = InMemoryPlaybackSessionRepository()
            val idempotencyStore = InMemoryIdempotencyStore()
            val outbox = RecordingOutbox()
            val caps = testCapabilitiesRegistry()

            val useCases =
                PlaybackUseCases(
                    sessionRepo = sessionRepo,
                    idempotencyStore = idempotencyStore,
                    capabilities = caps,
                    txExecutor = DirectTransactionalExecutor(),
                    outbox = outbox,
                    trackValidator = { it },
                    trackDurationLoader = { null },
                )

            // First: acquire lease to create session at version 1
            val ctx1 =
                CommandContext(userId, ProtocolId("JELLYFIN"), now, IdempotencyKey("k1"), AggregateVersion.INITIAL)
            val r1 = useCases.execute(AcquireLease(sessionId, deviceId, Duration.ofMinutes(5)), ctx1)
            r1.shouldBeInstanceOf<CommandResult.Success<PlaybackSessionAggregate>>()

            // Now try a command with wrong expectedVersion
            val ctx2 =
                CommandContext(userId, ProtocolId("JELLYFIN"), now, IdempotencyKey("k2"), AggregateVersion(99))
            val r2 = useCases.execute(RefreshLease(sessionId, deviceId, Duration.ofMinutes(5)), ctx2)
            r2.shouldBeInstanceOf<CommandResult.Failed>()
            r2.failure.shouldBeInstanceOf<Failure.Conflict>()
        }
    })
