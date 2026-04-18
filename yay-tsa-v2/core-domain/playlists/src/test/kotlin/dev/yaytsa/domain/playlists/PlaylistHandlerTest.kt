package dev.yaytsa.domain.playlists

import dev.yaytsa.shared.AggregateVersion
import dev.yaytsa.shared.CommandContext
import dev.yaytsa.shared.CommandResult
import dev.yaytsa.shared.Failure
import dev.yaytsa.shared.IdempotencyKey
import dev.yaytsa.shared.ProtocolId
import dev.yaytsa.shared.TrackId
import dev.yaytsa.shared.UserId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.time.Instant

class PlaylistHandlerTest :
    FunSpec({
        val userId = UserId("user-1")
        val plId = PlaylistId("pl-1")
        val now = Instant.parse("2025-01-01T12:00:00Z")
        val t1 = TrackId("t1")
        val t2 = TrackId("t2")
        val t3 = TrackId("t3")

        fun ctx(v: AggregateVersion = AggregateVersion(1)) = CommandContext(userId, ProtocolId("JELLYFIN"), now, IdempotencyKey("k"), v)

        fun deps(ids: Set<TrackId> = emptySet()) = PlaylistDeps(ids)

        fun pl(
            tracks: List<PlaylistEntry> = emptyList(),
            v: AggregateVersion = AggregateVersion(1),
        ) = PlaylistAggregate(plId, userId, "Test", null, false, tracks, now, now, v)

        test("CreatePlaylist creates new") {
            val r = PlaylistHandler.handle(null, CreatePlaylist(plId, userId, "My Playlist", null, false, now), ctx(AggregateVersion(0)), deps())
            r.shouldBeInstanceOf<CommandResult.Success<PlaylistAggregate>>()
            r.value.name shouldBe "My Playlist"
            r.value.tracks shouldBe emptyList()
        }

        test("CreatePlaylist on existing fails") {
            val r = PlaylistHandler.handle(pl(), CreatePlaylist(plId, userId, "Dup", null, false, now), ctx(), deps())
            r.shouldBeInstanceOf<CommandResult.Failed>()
            r.failure.shouldBeInstanceOf<Failure.InvariantViolation>()
        }

        test("CreatePlaylist with blank name fails") {
            val r = PlaylistHandler.handle(null, CreatePlaylist(plId, userId, "  ", null, false, now), ctx(AggregateVersion(0)), deps())
            r.shouldBeInstanceOf<CommandResult.Failed>()
            r.failure.shouldBeInstanceOf<Failure.InvariantViolation>()
        }

        test("RenamePlaylist updates name") {
            val r = PlaylistHandler.handle(pl(), RenamePlaylist(plId, "New Name"), ctx(), deps())
            r.shouldBeInstanceOf<CommandResult.Success<PlaylistAggregate>>()
            r.value.name shouldBe "New Name"
        }

        test("RenamePlaylist blank name fails") {
            val r = PlaylistHandler.handle(pl(), RenamePlaylist(plId, ""), ctx(), deps())
            r.shouldBeInstanceOf<CommandResult.Failed>()
            r.failure.shouldBeInstanceOf<Failure.InvariantViolation>()
        }

        test("AddTracksToPlaylist preserves order") {
            val r = PlaylistHandler.handle(pl(), AddTracksToPlaylist(plId, listOf(t1, t2, t3), now), ctx(), deps(setOf(t1, t2, t3)))
            r.shouldBeInstanceOf<CommandResult.Success<PlaylistAggregate>>()
            r.value.tracks.map { it.trackId } shouldBe listOf(t1, t2, t3)
        }

        test("AddTracksToPlaylist with unknown track fails") {
            val r = PlaylistHandler.handle(pl(), AddTracksToPlaylist(plId, listOf(t1), now), ctx(), deps())
            r.shouldBeInstanceOf<CommandResult.Failed>()
            r.failure.shouldBeInstanceOf<Failure.InvariantViolation>()
        }

        test("RemoveTracksFromPlaylist removes") {
            val tracks = listOf(PlaylistEntry(t1, now), PlaylistEntry(t2, now))
            val r = PlaylistHandler.handle(pl(tracks = tracks), RemoveTracksFromPlaylist(plId, listOf(t1)), ctx(), deps())
            r.shouldBeInstanceOf<CommandResult.Success<PlaylistAggregate>>()
            r.value.tracks.map { it.trackId } shouldBe listOf(t2)
        }

        test("RemoveTracksFromPlaylist for missing track fails") {
            val r = PlaylistHandler.handle(pl(), RemoveTracksFromPlaylist(plId, listOf(t1)), ctx(), deps())
            r.shouldBeInstanceOf<CommandResult.Failed>()
            r.failure.shouldBeInstanceOf<Failure.NotFound>()
        }

        test("ReorderPlaylistTracks valid permutation") {
            val tracks = listOf(PlaylistEntry(t1, now), PlaylistEntry(t2, now))
            val r = PlaylistHandler.handle(pl(tracks = tracks), ReorderPlaylistTracks(plId, listOf(t2, t1)), ctx(), deps())
            r.shouldBeInstanceOf<CommandResult.Success<PlaylistAggregate>>()
            r.value.tracks.map { it.trackId } shouldBe listOf(t2, t1)
        }

        test("ReorderPlaylistTracks invalid permutation fails") {
            val tracks = listOf(PlaylistEntry(t1, now))
            val r = PlaylistHandler.handle(pl(tracks = tracks), ReorderPlaylistTracks(plId, listOf(t2)), ctx(), deps())
            r.shouldBeInstanceOf<CommandResult.Failed>()
            r.failure.shouldBeInstanceOf<Failure.InvariantViolation>()
        }

        test("SetPlaylistVisibility") {
            val r = PlaylistHandler.handle(pl(), SetPlaylistVisibility(plId, true), ctx(), deps())
            r.shouldBeInstanceOf<CommandResult.Success<PlaylistAggregate>>()
            r.value.isPublic shouldBe true
        }

        test("non-owner cannot modify") {
            val r =
                PlaylistHandler.handle(
                    pl(),
                    RenamePlaylist(plId, "X"),
                    CommandContext(UserId("other"), ProtocolId("JELLYFIN"), now, IdempotencyKey("k"), AggregateVersion(1)),
                    deps(),
                )
            r.shouldBeInstanceOf<CommandResult.Failed>()
            r.failure.shouldBeInstanceOf<Failure.Unauthorized>()
        }

        test("version mismatch returns Conflict") {
            val r = PlaylistHandler.handle(pl(v = AggregateVersion(5)), RenamePlaylist(plId, "X"), ctx(AggregateVersion(3)), deps())
            r.shouldBeInstanceOf<CommandResult.Failed>()
            r.failure.shouldBeInstanceOf<Failure.Conflict>()
        }

        test("reorder with duplicate tracks preserves all entries") {
            val t1a = PlaylistEntry(t1, now)
            val t1b = PlaylistEntry(t1, now.plusSeconds(2))
            val t2a = PlaylistEntry(t2, now.plusSeconds(1))
            val pl = pl(tracks = listOf(t1a, t2a, t1b))

            val result =
                PlaylistHandler.handle(
                    pl,
                    ReorderPlaylistTracks(plId, listOf(t2, t1, t1)),
                    ctx(),
                    deps(),
                )
            result.shouldBeInstanceOf<CommandResult.Success<PlaylistAggregate>>()
            result.value.tracks.size shouldBe 3
            result.value.tracks[0].trackId shouldBe t2
            result.value.tracks[1].trackId shouldBe t1
            result.value.tracks[2].trackId shouldBe t1
        }

        test("reorder with wrong count fails") {
            val tracks = listOf(PlaylistEntry(t1, now), PlaylistEntry(t2, now))
            val result =
                PlaylistHandler.handle(
                    pl(tracks = tracks),
                    ReorderPlaylistTracks(plId, listOf(t1)),
                    ctx(),
                    deps(),
                )
            result.shouldBeInstanceOf<CommandResult.Failed>()
            result.failure.shouldBeInstanceOf<Failure.InvariantViolation>()
        }

        test("DeletePlaylist returns emptied aggregate") {
            val tracks = listOf(PlaylistEntry(t1, now), PlaylistEntry(t2, now))
            val r = PlaylistHandler.handle(pl(tracks = tracks), DeletePlaylist(plId), ctx(), deps())
            r.shouldBeInstanceOf<CommandResult.Success<PlaylistAggregate>>()
            r.value.tracks shouldBe emptyList()
            r.newVersion shouldBe AggregateVersion(2)
        }

        test("AddTracksToPlaylist to non-empty playlist appends") {
            val existing = listOf(PlaylistEntry(t1, now))
            val r =
                PlaylistHandler.handle(
                    pl(tracks = existing),
                    AddTracksToPlaylist(plId, listOf(t2), now),
                    ctx(),
                    deps(setOf(t2)),
                )
            r.shouldBeInstanceOf<CommandResult.Success<PlaylistAggregate>>()
            r.value.tracks.size shouldBe 2
            r.value.tracks[0].trackId shouldBe t1
            r.value.tracks[1].trackId shouldBe t2
        }

        test("updatedAt is set on rename") {
            val r = PlaylistHandler.handle(pl(), RenamePlaylist(plId, "New"), ctx(), deps())
            r.shouldBeInstanceOf<CommandResult.Success<PlaylistAggregate>>()
            r.value.updatedAt shouldBe now
        }

        test("reorder with duplicate trackId beyond multiplicity fails") {
            // playlist has [t1, t2], newOrder tries [t1, t1] — wrong multiplicity
            val tracks = listOf(PlaylistEntry(t1, now), PlaylistEntry(t2, now))
            val r =
                PlaylistHandler.handle(
                    pl(tracks = tracks),
                    ReorderPlaylistTracks(plId, listOf(t1, t1)),
                    ctx(),
                    deps(),
                )
            r.shouldBeInstanceOf<CommandResult.Failed>()
            r.failure.shouldBeInstanceOf<Failure.InvariantViolation>()
        }
    })
