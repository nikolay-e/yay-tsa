package dev.yaytsa.testkit.usecase

import dev.yaytsa.application.preferences.PreferencesUseCases
import dev.yaytsa.application.shared.port.DomainNotification
import dev.yaytsa.domain.preferences.SetFavorite
import dev.yaytsa.domain.preferences.UnsetFavorite
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
import dev.yaytsa.testkit.InMemoryUserPreferencesRepository
import dev.yaytsa.testkit.RecordingOutbox
import dev.yaytsa.testkit.testCapabilitiesRegistry
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.time.Instant

class PreferencesUseCasesTest :
    FunSpec({
        val userId = UserId("user-1")
        val jellyfinProtocol = ProtocolId("JELLYFIN")
        val now = Instant.parse("2025-01-01T12:00:00Z")
        val knownTrackIds = setOf(TrackId("t-1"), TrackId("t-2"))

        lateinit var repo: InMemoryUserPreferencesRepository
        lateinit var idempotencyStore: InMemoryIdempotencyStore
        lateinit var outbox: RecordingOutbox
        lateinit var useCases: PreferencesUseCases

        fun ctx(
            key: String = "key-1",
            version: AggregateVersion = AggregateVersion.INITIAL,
        ) = CommandContext(userId, jellyfinProtocol, now, IdempotencyKey(key), version)

        fun setFavoriteCmd(trackId: TrackId = TrackId("t-1")) = SetFavorite(userId, trackId, now)

        beforeEach {
            repo = InMemoryUserPreferencesRepository()
            idempotencyStore = InMemoryIdempotencyStore()
            outbox = RecordingOutbox()
            useCases =
                PreferencesUseCases(
                    prefsRepo = repo,
                    idempotencyStore = idempotencyStore,
                    capabilities = testCapabilitiesRegistry(),
                    txExecutor = DirectTransactionalExecutor(),
                    outbox = outbox,
                    trackValidator = { ids -> ids.filter { it in knownTrackIds }.toSet() },
                )
        }

        test("idempotent replay with same key and same payload returns same result") {
            val first = useCases.execute(setFavoriteCmd(), ctx())
            first.shouldBeInstanceOf<CommandResult.Success<*>>()
            outbox.notifications.size shouldBe 1

            val second = useCases.execute(setFavoriteCmd(), ctx())
            second.shouldBeInstanceOf<CommandResult.Success<*>>()
            outbox.notifications.size shouldBe 1
        }

        test("idempotent replay with same key but different payload returns InvariantViolation") {
            useCases.execute(setFavoriteCmd(), ctx())

            val result =
                useCases.execute(
                    SetFavorite(userId, TrackId("t-2"), now),
                    ctx(),
                )
            result.shouldBeInstanceOf<CommandResult.Failed>()
            result.failure.shouldBeInstanceOf<Failure.InvariantViolation>()
        }

        test("successful command enqueues exactly 1 PreferencesChanged notification") {
            useCases.execute(setFavoriteCmd(), ctx())

            outbox.notifications.size shouldBe 1
            outbox.notifications.single().shouldBeInstanceOf<DomainNotification.PreferencesChanged>()
        }

        test("failed command leaves outbox empty") {
            val result =
                useCases.execute(
                    UnsetFavorite(userId, TrackId("t-1")),
                    ctx(),
                )
            result.shouldBeInstanceOf<CommandResult.Failed>()
            outbox.notifications.size shouldBe 0
        }

        test("unsupported protocol returns UnsupportedByProtocol") {
            val result =
                useCases.execute(
                    setFavoriteCmd(),
                    CommandContext(userId, ProtocolId("UNKNOWN"), now, IdempotencyKey("key-1"), AggregateVersion.INITIAL),
                )
            result.shouldBeInstanceOf<CommandResult.Failed>()
            result.failure.shouldBeInstanceOf<Failure.UnsupportedByProtocol>()
        }

        test("SetFavorite with trackId not in library returns NotFound") {
            val result =
                useCases.execute(
                    SetFavorite(userId, TrackId("not-in-library"), now),
                    ctx(),
                )
            result.shouldBeInstanceOf<CommandResult.Failed>()
            result.failure.shouldBeInstanceOf<Failure.NotFound>()
        }
    })
