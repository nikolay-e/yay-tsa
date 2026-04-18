package dev.yaytsa.testkit.usecase

import dev.yaytsa.application.playlists.PlaylistUseCases
import dev.yaytsa.application.shared.port.DomainNotification
import dev.yaytsa.domain.playlists.AddTracksToPlaylist
import dev.yaytsa.domain.playlists.CreatePlaylist
import dev.yaytsa.domain.playlists.DeletePlaylist
import dev.yaytsa.domain.playlists.PlaylistId
import dev.yaytsa.domain.playlists.RenamePlaylist
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
        val playlistId = PlaylistId("pl-1")
        val jellyfinProtocol = ProtocolId("JELLYFIN")
        val now = Instant.parse("2025-01-01T12:00:00Z")
        val knownTrackIds = setOf(TrackId("t-1"), TrackId("t-2"))

        lateinit var repo: InMemoryPlaylistRepository
        lateinit var idempotencyStore: InMemoryIdempotencyStore
        lateinit var outbox: RecordingOutbox
        lateinit var useCases: PlaylistUseCases

        fun ctx(
            key: String = "key-1",
            version: AggregateVersion = AggregateVersion.INITIAL,
        ) = CommandContext(userId, jellyfinProtocol, now, IdempotencyKey(key), version)

        fun createPlaylist() =
            useCases.execute(
                CreatePlaylist(playlistId, userId, "My Playlist", null, false, now),
                ctx(),
            )

        beforeEach {
            repo = InMemoryPlaylistRepository()
            idempotencyStore = InMemoryIdempotencyStore()
            outbox = RecordingOutbox()
            useCases =
                PlaylistUseCases(
                    playlistRepo = repo,
                    idempotencyStore = idempotencyStore,
                    capabilities = testCapabilitiesRegistry(),
                    txExecutor = DirectTransactionalExecutor(),
                    outbox = outbox,
                    trackValidator = { ids -> ids.filter { it in knownTrackIds }.toSet() },
                )
        }

        test("idempotent replay with same key and same payload returns same result") {
            val first = createPlaylist()
            first.shouldBeInstanceOf<CommandResult.Success<*>>()

            outbox.notifications.size shouldBe 1

            val second =
                useCases.execute(
                    CreatePlaylist(playlistId, userId, "My Playlist", null, false, now),
                    ctx(),
                )
            second.shouldBeInstanceOf<CommandResult.Success<*>>()
            outbox.notifications.size shouldBe 1
        }

        test("idempotent replay with same key but different payload returns InvariantViolation") {
            createPlaylist()

            val result =
                useCases.execute(
                    CreatePlaylist(playlistId, userId, "Different Name", null, false, now),
                    ctx(),
                )
            result.shouldBeInstanceOf<CommandResult.Failed>()
            result.failure.shouldBeInstanceOf<Failure.InvariantViolation>()
        }

        test("two different command types with same idempotency key both succeed") {
            createPlaylist()

            val version = (createPlaylist() as CommandResult.Success).newVersion
            val renameResult =
                useCases.execute(
                    RenamePlaylist(playlistId, "Renamed"),
                    ctx(key = "key-1", version = version),
                )
            renameResult.shouldBeInstanceOf<CommandResult.Success<*>>()
        }

        test("successful command enqueues exactly 1 PlaylistChanged notification") {
            createPlaylist()

            outbox.notifications.size shouldBe 1
            outbox.notifications.single().shouldBeInstanceOf<DomainNotification.PlaylistChanged>()
        }

        test("failed command leaves outbox empty") {
            val result =
                useCases.execute(
                    CreatePlaylist(playlistId, userId, "", null, false, now),
                    ctx(),
                )
            result.shouldBeInstanceOf<CommandResult.Failed>()
            outbox.notifications.size shouldBe 0
        }

        test("idempotent replay does not duplicate outbox notification") {
            createPlaylist()
            useCases.execute(
                CreatePlaylist(playlistId, userId, "My Playlist", null, false, now),
                ctx(),
            )

            outbox.notifications.size shouldBe 1
        }

        test("unsupported protocol returns UnsupportedByProtocol") {
            val result =
                useCases.execute(
                    CreatePlaylist(playlistId, userId, "My Playlist", null, false, now),
                    CommandContext(userId, ProtocolId("UNKNOWN"), now, IdempotencyKey("key-1"), AggregateVersion.INITIAL),
                )
            result.shouldBeInstanceOf<CommandResult.Failed>()
            result.failure.shouldBeInstanceOf<Failure.UnsupportedByProtocol>()
        }

        test("JELLYFIN protocol succeeds") {
            val result = createPlaylist()
            result.shouldBeInstanceOf<CommandResult.Success<*>>()
        }

        test("AddTracksToPlaylist with unknown trackId returns InvariantViolation") {
            val created = createPlaylist()
            val version = (created as CommandResult.Success).newVersion

            val result =
                useCases.execute(
                    AddTracksToPlaylist(playlistId, listOf(TrackId("unknown-track")), now),
                    ctx(key = "key-add", version = version),
                )
            result.shouldBeInstanceOf<CommandResult.Failed>()
            result.failure.shouldBeInstanceOf<Failure.InvariantViolation>()
        }

        test("Idempotent replay of DeletePlaylist returns success") {
            val created = createPlaylist()
            val createVersion = (created as CommandResult.Success).newVersion

            val deleteResult =
                useCases.execute(
                    DeletePlaylist(playlistId),
                    ctx(key = "key-delete", version = createVersion),
                )
            deleteResult.shouldBeInstanceOf<CommandResult.Success<*>>()

            val replay =
                useCases.execute(
                    DeletePlaylist(playlistId),
                    ctx(key = "key-delete", version = createVersion),
                )
            replay.shouldBeInstanceOf<CommandResult.Success<*>>()
        }
    })
