package dev.yaytsa.application.playback

import dev.yaytsa.application.shared.port.DomainNotification
import dev.yaytsa.domain.playback.AcquireLease
import dev.yaytsa.domain.playback.DeviceId
import dev.yaytsa.domain.playback.Play
import dev.yaytsa.domain.playback.PlaybackSessionAggregate
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

class PlaybackUseCasesTest :
    FunSpec({
        val userId = UserId("user-1")
        val sessionId = SessionId("session-1")
        val deviceA = DeviceId("dev-A")
        val now = Instant.parse("2025-01-01T12:00:00Z")

        fun ctx(v: AggregateVersion = AggregateVersion.INITIAL) = CommandContext(userId, ProtocolId("JELLYFIN"), now, IdempotencyKey("k-${System.nanoTime()}"), v)

        test("successful command enqueues PlaybackStateChanged notification") {
            val outbox = RecordingOutbox()
            val sessionRepo = InMemoryPlaybackSessionRepository()
            val useCases =
                PlaybackUseCases(
                    sessionRepo,
                    InMemoryIdempotencyStore(),
                    testCapabilitiesRegistry(),
                    DirectTransactionalExecutor(),
                    outbox,
                    { it },
                    { Duration.ofMinutes(3) },
                )

            val result =
                useCases.execute(
                    AcquireLease(sessionId, deviceA, Duration.ofSeconds(60)),
                    ctx(),
                )
            result.shouldBeInstanceOf<CommandResult.Success<PlaybackSessionAggregate>>()
            outbox.notifications.size shouldBe 1
            outbox.notifications[0].shouldBeInstanceOf<DomainNotification.PlaybackStateChanged>()
        }

        test("failed command does not enqueue notification") {
            val outbox = RecordingOutbox()
            val sessionRepo = InMemoryPlaybackSessionRepository()
            val useCases =
                PlaybackUseCases(
                    sessionRepo,
                    InMemoryIdempotencyStore(),
                    testCapabilitiesRegistry(),
                    DirectTransactionalExecutor(),
                    outbox,
                    { it },
                    { Duration.ofMinutes(3) },
                )

            // Play without lease -> fails
            val result = useCases.execute(Play(sessionId, deviceA), ctx())
            result.shouldBeInstanceOf<CommandResult.Failed>()
            outbox.notifications.size shouldBe 0
        }

        test("idempotent replay with different payload returns InvariantViolation") {
            val outbox = RecordingOutbox()
            val idemStore = InMemoryIdempotencyStore()
            val useCases =
                PlaybackUseCases(
                    InMemoryPlaybackSessionRepository(),
                    idemStore,
                    testCapabilitiesRegistry(),
                    DirectTransactionalExecutor(),
                    outbox,
                    { it },
                    { Duration.ofMinutes(3) },
                )

            val key = IdempotencyKey("same-key")
            val ctx1 = CommandContext(userId, ProtocolId("JELLYFIN"), now, key, AggregateVersion.INITIAL)
            useCases.execute(AcquireLease(sessionId, deviceA, Duration.ofSeconds(60)), ctx1)

            // Same key, different payload
            val ctx2 = CommandContext(userId, ProtocolId("JELLYFIN"), now, key, AggregateVersion(1))
            val result = useCases.execute(AcquireLease(sessionId, deviceA, Duration.ofSeconds(120)), ctx2)
            result.shouldBeInstanceOf<CommandResult.Failed>()
            result.failure.shouldBeInstanceOf<Failure.InvariantViolation>()
        }
    })
