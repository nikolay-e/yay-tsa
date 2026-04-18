package dev.yaytsa.application.playlists

import dev.yaytsa.application.shared.port.DomainNotification
import dev.yaytsa.domain.playlists.CreatePlaylist
import dev.yaytsa.domain.playlists.PlaylistAggregate
import dev.yaytsa.domain.playlists.PlaylistId
import dev.yaytsa.domain.playlists.RenamePlaylist
import dev.yaytsa.shared.AggregateVersion
import dev.yaytsa.shared.CommandContext
import dev.yaytsa.shared.CommandResult
import dev.yaytsa.shared.IdempotencyKey
import dev.yaytsa.shared.ProtocolId
import dev.yaytsa.shared.UserId
import dev.yaytsa.testkit.DirectTransactionalExecutor
import dev.yaytsa.testkit.InMemoryIdempotencyStore
import dev.yaytsa.testkit.InMemoryPlaylistRepository
import dev.yaytsa.testkit.RecordingOutbox
import dev.yaytsa.testkit.testCapabilitiesRegistry
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.time.Instant

class PlaylistUseCasesTest :
    FunSpec({
        val userId = UserId("user-1")
        val plId = PlaylistId("pl-1")
        val now = Instant.parse("2025-01-01T12:00:00Z")

        fun ctx(v: AggregateVersion = AggregateVersion.INITIAL) = CommandContext(userId, ProtocolId("JELLYFIN"), now, IdempotencyKey("k-${System.nanoTime()}"), v)

        test("successful command enqueues PlaylistChanged notification") {
            val outbox = RecordingOutbox()
            val useCases =
                PlaylistUseCases(
                    InMemoryPlaylistRepository(),
                    InMemoryIdempotencyStore(),
                    testCapabilitiesRegistry(),
                    DirectTransactionalExecutor(),
                    outbox,
                    { it },
                )

            val result =
                useCases.execute(
                    CreatePlaylist(plId, userId, "My Playlist", null, false, now),
                    ctx(),
                )
            result.shouldBeInstanceOf<CommandResult.Success<PlaylistAggregate>>()
            outbox.notifications.size shouldBe 1
            outbox.notifications[0].shouldBeInstanceOf<DomainNotification.PlaylistChanged>()
        }

        test("failed command does not enqueue notification") {
            val outbox = RecordingOutbox()
            val playlistRepo = InMemoryPlaylistRepository()
            val useCases =
                PlaylistUseCases(
                    playlistRepo,
                    InMemoryIdempotencyStore(),
                    testCapabilitiesRegistry(),
                    DirectTransactionalExecutor(),
                    outbox,
                    { it },
                )

            // Rename on non-existent playlist -> fails
            val result =
                useCases.execute(
                    RenamePlaylist(plId, "New Name"),
                    ctx(AggregateVersion(1)),
                )
            result.shouldBeInstanceOf<CommandResult.Failed>()
            outbox.notifications.size shouldBe 0
        }
    })
