package dev.yaytsa.testkit.property

import dev.yaytsa.domain.playlists.AddTracksToPlaylist
import dev.yaytsa.domain.playlists.CreatePlaylist
import dev.yaytsa.domain.playlists.PlaylistAggregate
import dev.yaytsa.domain.playlists.PlaylistDeps
import dev.yaytsa.domain.playlists.PlaylistHandler
import dev.yaytsa.domain.playlists.PlaylistId
import dev.yaytsa.domain.playlists.ReorderPlaylistTracks
import dev.yaytsa.shared.AggregateVersion
import dev.yaytsa.shared.CommandContext
import dev.yaytsa.shared.CommandResult
import dev.yaytsa.shared.IdempotencyKey
import dev.yaytsa.shared.ProtocolId
import dev.yaytsa.shared.TrackId
import dev.yaytsa.shared.UserId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.property.Arb
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.checkAll
import java.time.Instant

class PlaylistPropertyTest :
    FunSpec({
        val userId = UserId("u-pl")
        val playlistId = PlaylistId("pl-1")
        val now = Instant.parse("2025-06-01T12:00:00Z")

        val trackPool = (0..29).map { TrackId("t-$it") }
        val knownTrackIds = trackPool.toSet()
        val deps = PlaylistDeps(knownTrackIds = knownTrackIds)

        fun ctx(
            version: AggregateVersion,
            key: String = "k",
        ) = CommandContext(
            userId = userId,
            protocolId = ProtocolId("JELLYFIN"),
            requestTime = now,
            idempotencyKey = IdempotencyKey(key),
            expectedVersion = version,
        )

        fun createPlaylist(): PlaylistAggregate {
            val cmd = CreatePlaylist(playlistId, userId, "Test", null, false, now)
            val result = PlaylistHandler.handle(null, cmd, ctx(AggregateVersion.INITIAL, "create"), deps)
            return (result as CommandResult.Success).value
        }

        fun addTracks(
            playlist: PlaylistAggregate,
            trackIds: List<TrackId>,
        ): PlaylistAggregate {
            val cmd = AddTracksToPlaylist(playlistId, trackIds, now)
            val result = PlaylistHandler.handle(playlist, cmd, ctx(playlist.version, "add"), deps)
            return (result as CommandResult.Success).value
        }

        test("ReorderPlaylistTracks with any valid permutation preserves tracks and metadata") {
            checkAll(200, Arb.int(1..20), Arb.list(Arb.int(0..1000), 20..20)) { trackCount, shuffleSeeds ->
                val tracks = trackPool.take(trackCount)
                var playlist = createPlaylist()
                playlist = addTracks(playlist, tracks)

                val currentTrackIds = playlist.tracks.map { it.trackId }
                val permuted = currentTrackIds.toMutableList()

                for (seed in shuffleSeeds.take(trackCount)) {
                    if (permuted.size < 2) break
                    val a = seed % permuted.size
                    val b = (seed / 7 + 1) % permuted.size
                    val tmp = permuted[a]
                    permuted[a] = permuted[b]
                    permuted[b] = tmp
                }

                val cmd = ReorderPlaylistTracks(playlistId, permuted)
                val result = PlaylistHandler.handle(playlist, cmd, ctx(playlist.version, "reorder"), deps)

                result.shouldBeInstanceOf<CommandResult.Success<PlaylistAggregate>>()
                val reordered = result.value

                reordered.tracks.map { it.trackId } shouldBe permuted
                reordered.tracks.map { it.trackId }.toSet() shouldBe playlist.tracks.map { it.trackId }.toSet()

                val originalAddedAt = playlist.tracks.associate { it.trackId to it.addedAt }
                for (entry in reordered.tracks) {
                    entry.addedAt shouldBe originalAddedAt[entry.trackId]
                }
            }
        }

        test("ReorderPlaylistTracks rejects non-permutations") {
            checkAll(100, Arb.int(2..15)) { trackCount ->
                val tracks = trackPool.take(trackCount)
                var playlist = createPlaylist()
                playlist = addTracks(playlist, tracks)

                val wrongOrder = playlist.tracks.map { it.trackId }.drop(1)
                val cmd = ReorderPlaylistTracks(playlistId, wrongOrder)
                val result = PlaylistHandler.handle(playlist, cmd, ctx(playlist.version, "bad-reorder"), deps)

                result.shouldBeInstanceOf<CommandResult.Failed>()
            }
        }
    })
