package dev.yaytsa.application.preferences

import dev.yaytsa.application.shared.port.DomainNotification
import dev.yaytsa.domain.preferences.SetFavorite
import dev.yaytsa.domain.preferences.UnsetFavorite
import dev.yaytsa.domain.preferences.UserPreferencesAggregate
import dev.yaytsa.shared.AggregateVersion
import dev.yaytsa.shared.CommandContext
import dev.yaytsa.shared.CommandResult
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
        val trackId = TrackId("track-1")
        val now = Instant.parse("2025-01-01T12:00:00Z")

        fun ctx(v: AggregateVersion = AggregateVersion.INITIAL) = CommandContext(userId, ProtocolId("JELLYFIN"), now, IdempotencyKey("k-${System.nanoTime()}"), v)

        test("successful command enqueues PreferencesChanged notification") {
            val outbox = RecordingOutbox()
            val useCases =
                PreferencesUseCases(
                    InMemoryUserPreferencesRepository(),
                    InMemoryIdempotencyStore(),
                    testCapabilitiesRegistry(),
                    DirectTransactionalExecutor(),
                    outbox,
                    { it },
                )

            val result =
                useCases.execute(
                    SetFavorite(userId, trackId, now),
                    ctx(),
                )
            result.shouldBeInstanceOf<CommandResult.Success<UserPreferencesAggregate>>()
            outbox.notifications.size shouldBe 1
            outbox.notifications[0].shouldBeInstanceOf<DomainNotification.PreferencesChanged>()
        }

        test("failed command does not enqueue notification") {
            val outbox = RecordingOutbox()
            val useCases =
                PreferencesUseCases(
                    InMemoryUserPreferencesRepository(),
                    InMemoryIdempotencyStore(),
                    testCapabilitiesRegistry(),
                    DirectTransactionalExecutor(),
                    outbox,
                    { it },
                )

            // UnsetFavorite on non-existent favorite -> fails
            val result =
                useCases.execute(
                    UnsetFavorite(userId, trackId),
                    ctx(),
                )
            result.shouldBeInstanceOf<CommandResult.Failed>()
            outbox.notifications.size shouldBe 0
        }
    })
