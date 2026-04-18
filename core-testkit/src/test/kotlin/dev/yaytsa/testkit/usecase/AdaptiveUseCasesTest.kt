package dev.yaytsa.testkit.usecase

import dev.yaytsa.application.adaptive.AdaptiveUseCases
import dev.yaytsa.application.shared.port.DomainNotification
import dev.yaytsa.domain.adaptive.EndListeningSession
import dev.yaytsa.domain.adaptive.ListeningSessionId
import dev.yaytsa.domain.adaptive.StartListeningSession
import dev.yaytsa.shared.AggregateVersion
import dev.yaytsa.shared.CommandContext
import dev.yaytsa.shared.CommandResult
import dev.yaytsa.shared.EntityId
import dev.yaytsa.shared.Failure
import dev.yaytsa.shared.IdempotencyKey
import dev.yaytsa.shared.ProtocolId
import dev.yaytsa.shared.TrackId
import dev.yaytsa.shared.UserId
import dev.yaytsa.testkit.DirectTransactionalExecutor
import dev.yaytsa.testkit.InMemoryAdaptiveSessionRepository
import dev.yaytsa.testkit.InMemoryIdempotencyStore
import dev.yaytsa.testkit.InMemoryPlaybackSignalWritePort
import dev.yaytsa.testkit.RecordingOutbox
import dev.yaytsa.testkit.testCapabilitiesRegistry
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.time.Instant

class AdaptiveUseCasesTest :
    FunSpec({
        val userId = UserId("user-1")
        val sessionId = ListeningSessionId("adaptive-session-1")
        val jellyfinProtocol = ProtocolId("JELLYFIN")
        val now = Instant.parse("2025-01-01T12:00:00Z")
        val knownTracks = setOf(TrackId("t-1"), TrackId("t-2"))

        lateinit var repo: InMemoryAdaptiveSessionRepository
        lateinit var idempotencyStore: InMemoryIdempotencyStore
        lateinit var outbox: RecordingOutbox
        lateinit var signalWriter: InMemoryPlaybackSignalWritePort
        lateinit var useCases: AdaptiveUseCases

        fun ctx(
            key: String = "key-1",
            version: AggregateVersion = AggregateVersion.INITIAL,
        ) = CommandContext(userId, jellyfinProtocol, now, IdempotencyKey(key), version)

        fun startSessionCmd() =
            StartListeningSession(
                sessionId = sessionId,
                attentionMode = "focus",
                seedTrackId = EntityId("seed-1"),
                seedGenres = listOf("jazz"),
            )

        beforeEach {
            repo = InMemoryAdaptiveSessionRepository()
            idempotencyStore = InMemoryIdempotencyStore()
            outbox = RecordingOutbox()
            signalWriter = InMemoryPlaybackSignalWritePort()
            useCases =
                AdaptiveUseCases(
                    sessionRepo = repo,
                    idempotencyStore = idempotencyStore,
                    capabilities = testCapabilitiesRegistry(),
                    txExecutor = DirectTransactionalExecutor(),
                    outbox = outbox,
                    trackValidator = { ids -> ids.filter { it in knownTracks }.toSet() },
                    signalWriter = signalWriter,
                )
        }

        test("idempotent replay with same key and payload returns same result") {
            val first = useCases.execute(startSessionCmd(), ctx())
            first.shouldBeInstanceOf<CommandResult.Success<*>>()
            outbox.notifications.size shouldBe 1

            val second = useCases.execute(startSessionCmd(), ctx())
            second.shouldBeInstanceOf<CommandResult.Success<*>>()
            outbox.notifications.size shouldBe 1
        }

        test("idempotent replay with same key but different payload returns InvariantViolation") {
            useCases.execute(startSessionCmd(), ctx()).shouldBeInstanceOf<CommandResult.Success<*>>()

            val differentCmd =
                StartListeningSession(
                    sessionId = sessionId,
                    attentionMode = "background",
                    seedTrackId = EntityId("seed-2"),
                    seedGenres = listOf("rock"),
                )
            val result = useCases.execute(differentCmd, ctx())
            result.shouldBeInstanceOf<CommandResult.Failed>()
            result.failure.shouldBeInstanceOf<Failure.InvariantViolation>()
        }

        test("successful command enqueues outbox notification") {
            useCases.execute(startSessionCmd(), ctx()).shouldBeInstanceOf<CommandResult.Success<*>>()

            outbox.notifications.size shouldBe 1
            outbox.notifications.single().shouldBeInstanceOf<DomainNotification.AdaptiveQueueChanged>()
        }

        test("failed command does not enqueue outbox notification") {
            val result =
                useCases.execute(
                    EndListeningSession(sessionId, "summary"),
                    ctx(),
                )
            result.shouldBeInstanceOf<CommandResult.Failed>()
            outbox.notifications.size shouldBe 0
        }

        test("unsupported protocol returns UnsupportedByProtocol") {
            val result =
                useCases.execute(
                    startSessionCmd(),
                    CommandContext(userId, ProtocolId("UNKNOWN"), now, IdempotencyKey("key-1"), AggregateVersion.INITIAL),
                )
            result.shouldBeInstanceOf<CommandResult.Failed>()
            result.failure.shouldBeInstanceOf<Failure.UnsupportedByProtocol>()
        }
    })
