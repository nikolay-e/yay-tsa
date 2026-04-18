package dev.yaytsa.testkit.property

import dev.yaytsa.domain.playback.AcquireLease
import dev.yaytsa.domain.playback.DeviceId
import dev.yaytsa.domain.playback.PlaybackDeps
import dev.yaytsa.domain.playback.PlaybackHandler
import dev.yaytsa.domain.playback.PlaybackSessionAggregate
import dev.yaytsa.domain.playback.SessionId
import dev.yaytsa.domain.playlists.CreatePlaylist
import dev.yaytsa.domain.playlists.PlaylistAggregate
import dev.yaytsa.domain.playlists.PlaylistDeps
import dev.yaytsa.domain.playlists.PlaylistHandler
import dev.yaytsa.domain.playlists.PlaylistId
import dev.yaytsa.domain.playlists.RenamePlaylist
import dev.yaytsa.domain.preferences.PreferencesCommand
import dev.yaytsa.domain.preferences.PreferencesDeps
import dev.yaytsa.domain.preferences.PreferencesHandler
import dev.yaytsa.domain.preferences.ReorderFavorites
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
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.property.Arb
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.checkAll
import java.time.Duration
import java.time.Instant

class PreferencesPropertyTest :
    FunSpec({
        val userId = UserId("u-pref")
        val now = Instant.parse("2025-06-01T12:00:00Z")

        val trackPool = (0..29).map { TrackId("t-$it") }
        val knownTrackIds = trackPool.toSet()
        val deps = PreferencesDeps(knownTrackIds = knownTrackIds)

        fun ctx(
            version: AggregateVersion,
            key: String,
        ) = CommandContext(
            userId = userId,
            protocolId = ProtocolId("JELLYFIN"),
            requestTime = now,
            idempotencyKey = IdempotencyKey(key),
            expectedVersion = version,
        )

        test("favorites positions are always unique after any sequence of Set/Unset/Reorder operations") {
            checkAll(
                150,
                Arb.list(Arb.int(0..2), 1..25),
                Arb.list(Arb.int(0..29), 25..25),
                Arb.list(Arb.int(0..1000), 25..25),
            ) { ops, trackIndices, shuffleSeeds ->
                var prefs = UserPreferencesAggregate.empty(userId)
                var keyCounter = 0

                for ((i, opCode) in ops.withIndex()) {
                    val version = prefs.version
                    val cmdCtx = ctx(version, "k-${keyCounter++}")
                    val trackIdx = trackIndices.getOrElse(i) { 0 }

                    val cmd: PreferencesCommand =
                        when (opCode) {
                            0 -> SetFavorite(userId, trackPool[trackIdx % trackPool.size], now)
                            1 -> {
                                if (prefs.favorites.isEmpty()) continue
                                val target = prefs.favorites[trackIdx % prefs.favorites.size].trackId
                                UnsetFavorite(userId, target)
                            }
                            2 -> {
                                if (prefs.favorites.size < 2) continue
                                val ids = prefs.favorites.map { it.trackId }.toMutableList()
                                val seed = shuffleSeeds.getOrElse(i) { 0 }
                                val a = seed % ids.size
                                val b = (seed / 7 + 1) % ids.size
                                val tmp = ids[a]
                                ids[a] = ids[b]
                                ids[b] = tmp
                                ReorderFavorites(userId, ids)
                            }
                            else -> continue
                        }

                    when (val result = PreferencesHandler.handle(prefs, cmd, cmdCtx, deps)) {
                        is CommandResult.Success -> prefs = result.value
                        is CommandResult.Failed -> {}
                    }

                    val positions = prefs.favorites.map { it.position }
                    positions shouldBe positions.distinct()

                    val trackIds = prefs.favorites.map { it.trackId }
                    trackIds shouldBe trackIds.distinct()
                }
            }
        }

        test("OCC monotonicity - PreferencesHandler result version equals input version plus one") {
            checkAll(200, Arb.int(0..29), Arb.int(0..100)) { trackIdx, startVer ->
                val startVersion = AggregateVersion(startVer.toLong())
                val prefs = UserPreferencesAggregate.empty(userId).copy(version = startVersion)
                val cmd = SetFavorite(userId, trackPool[trackIdx], now)
                val result = PreferencesHandler.handle(prefs, cmd, ctx(prefs.version, "occ-pref"), deps)

                result.shouldBeInstanceOf<CommandResult.Success<UserPreferencesAggregate>>()
                result.newVersion shouldBe startVersion.next()
            }
        }

        test("OCC monotonicity - PlaybackHandler result version equals input version plus one") {
            val sessionId = SessionId("s-occ")
            val deviceId = DeviceId("d-occ")
            val playbackDeps = PlaybackDeps(knownTrackIds = emptySet(), currentTrackDuration = null)

            checkAll(200, Arb.int(1..120), Arb.int(0..100)) { durationMinutes, startVer ->
                val startVersion = AggregateVersion(startVer.toLong())
                val session = PlaybackSessionAggregate.empty(userId, sessionId, now).copy(version = startVersion)
                val cmd = AcquireLease(sessionId, deviceId, Duration.ofMinutes(durationMinutes.toLong()))
                val result = PlaybackHandler.handle(session, cmd, ctx(session.version, "occ-pb"), playbackDeps)

                result.shouldBeInstanceOf<CommandResult.Success<PlaybackSessionAggregate>>()
                result.newVersion shouldBe startVersion.next()
            }
        }

        test("OCC monotonicity - PlaylistHandler result version equals input version plus one") {
            val playlistId = PlaylistId("pl-occ")
            val playlistDeps = PlaylistDeps(knownTrackIds = knownTrackIds)

            // CreatePlaylist always produces INITIAL.next() — test rename with random versions
            val createCmd = CreatePlaylist(playlistId, userId, "Test", null, false, now)
            val createResult = PlaylistHandler.handle(null, createCmd, ctx(AggregateVersion.INITIAL, "occ-create"), playlistDeps)
            createResult.shouldBeInstanceOf<CommandResult.Success<PlaylistAggregate>>()
            createResult.newVersion shouldBe AggregateVersion.INITIAL.next()

            checkAll(100, Arb.int(1..100)) { startVer ->
                val startVersion = AggregateVersion(startVer.toLong())
                val playlist = createResult.value.copy(version = startVersion)
                val renameCmd = RenamePlaylist(playlistId, "Renamed")
                val renameResult = PlaylistHandler.handle(playlist, renameCmd, ctx(startVersion, "occ-rename"), playlistDeps)
                renameResult.shouldBeInstanceOf<CommandResult.Success<PlaylistAggregate>>()
                renameResult.newVersion shouldBe startVersion.next()
            }
        }
    })
