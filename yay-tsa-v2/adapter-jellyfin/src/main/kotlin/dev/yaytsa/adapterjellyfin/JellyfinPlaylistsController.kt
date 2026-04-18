package dev.yaytsa.adapterjellyfin

import dev.yaytsa.application.library.LibraryQueries
import dev.yaytsa.application.playlists.PlaylistQueries
import dev.yaytsa.application.playlists.PlaylistUseCases
import dev.yaytsa.application.preferences.PreferencesQueries
import dev.yaytsa.application.shared.port.Clock
import dev.yaytsa.domain.playlists.AddTracksToPlaylist
import dev.yaytsa.domain.playlists.CreatePlaylist
import dev.yaytsa.domain.playlists.DeletePlaylist
import dev.yaytsa.domain.playlists.PlaylistId
import dev.yaytsa.domain.playlists.RemoveTracksFromPlaylist
import dev.yaytsa.domain.playlists.ReorderPlaylistTracks
import dev.yaytsa.shared.AggregateVersion
import dev.yaytsa.shared.CommandContext
import dev.yaytsa.shared.CommandResult
import dev.yaytsa.shared.EntityId
import dev.yaytsa.shared.IdempotencyKey
import dev.yaytsa.shared.ProtocolId
import dev.yaytsa.shared.TrackId
import dev.yaytsa.shared.UserId
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.security.Principal
import java.util.UUID

@RestController
class JellyfinPlaylistsController(
    private val playlistQueries: PlaylistQueries,
    private val playlistUseCases: PlaylistUseCases,
    private val libraryQueries: LibraryQueries,
    private val preferencesQueries: PreferencesQueries,
    private val clock: Clock,
) {
    private fun isValidUuid(value: String): Boolean =
        try {
            UUID.fromString(value)
            true
        } catch (_: IllegalArgumentException) {
            false
        }

    data class CreatePlaylistRequest(
        val Name: String,
        val UserId: String,
        val Ids: List<String>? = null,
        val IsPublic: Boolean? = null,
    )

    @PostMapping("/Playlists")
    fun createPlaylist(
        @RequestBody request: CreatePlaylistRequest,
    ): ResponseEntity<Any> {
        val uid = UserId(request.UserId)
        val pid = PlaylistId(UUID.randomUUID().toString())
        val now = clock.now()
        val cmd = CreatePlaylist(pid, uid, request.Name, null, request.IsPublic ?: false, now)
        val ctx = CommandContext(uid, ProtocolId("JELLYFIN"), now, IdempotencyKey(UUID.randomUUID().toString()), AggregateVersion.INITIAL)
        val result = playlistUseCases.execute(cmd, ctx)
        return when (result) {
            is CommandResult.Success -> ResponseEntity.ok(mapOf("Id" to pid.value))
            is CommandResult.Failed -> ResponseEntity.badRequest().body(mapOf("error" to result.failure.toString()))
        }
    }

    @GetMapping("/Playlists/{playlistId}/Items")
    fun getPlaylistItems(
        @PathVariable playlistId: String,
        @RequestParam(name = "UserId", required = false) userId: String?,
        @RequestParam(name = "StartIndex", required = false, defaultValue = "0") startIndex: Int,
        @RequestParam(name = "Limit", required = false, defaultValue = "100") limit: Int,
        principal: Principal?,
    ): ResponseEntity<Any> {
        if (!isValidUuid(playlistId)) return ResponseEntity.badRequest().build()
        val playlist =
            playlistQueries.find(PlaylistId(playlistId))
                ?: return ResponseEntity.notFound().build()

        val uid = userId ?: principal?.name
        val favTrackIds =
            if (uid != null) {
                (preferencesQueries.find(UserId(uid))?.favorites ?: emptyList())
                    .map { it.trackId.value }
                    .toSet()
            } else {
                emptySet()
            }

        val items =
            playlist.tracks.drop(startIndex).take(limit).mapNotNull { entry ->
                libraryQueries.getTrack(EntityId(entry.trackId.value))?.let { track ->
                    BaseItem(
                        id = track.id.value,
                        name = track.name,
                        type = "Audio",
                        runTimeTicks = msToTicks(track.durationMs),
                        playlistItemId = entry.trackId.value,
                        userData = UserItemData(isFavorite = track.id.value in favTrackIds),
                    )
                }
            }
        return ResponseEntity.ok(ItemsResult(items, playlist.tracks.size, startIndex))
    }

    @PostMapping("/Playlists/{playlistId}/Items")
    fun addToPlaylist(
        @PathVariable playlistId: String,
        @RequestParam(name = "Ids") ids: String,
        @RequestParam(name = "UserId", required = false) userId: String?,
        principal: Principal,
    ): ResponseEntity<Void> {
        if (!isValidUuid(playlistId)) return ResponseEntity.badRequest().build()
        val uid = UserId(userId ?: principal.name)
        val playlist = playlistQueries.find(PlaylistId(playlistId)) ?: return ResponseEntity.notFound().build()
        val trackIds = ids.split(",").map { TrackId(it.trim()) }
        val cmd = AddTracksToPlaylist(PlaylistId(playlistId), trackIds, clock.now())
        val ctx = CommandContext(uid, ProtocolId("JELLYFIN"), clock.now(), IdempotencyKey(UUID.randomUUID().toString()), playlist.version)
        playlistUseCases.execute(cmd, ctx)
        return ResponseEntity.noContent().build()
    }

    @DeleteMapping("/Playlists/{playlistId}/Items")
    fun removeFromPlaylist(
        @PathVariable playlistId: String,
        @RequestParam(name = "EntryIds") entryIds: String,
        principal: Principal,
    ): ResponseEntity<Void> {
        if (!isValidUuid(playlistId)) return ResponseEntity.badRequest().build()
        val uid = UserId(principal.name)
        val playlist = playlistQueries.find(PlaylistId(playlistId)) ?: return ResponseEntity.notFound().build()
        val trackIds = entryIds.split(",").map { TrackId(it.trim()) }
        val cmd = RemoveTracksFromPlaylist(PlaylistId(playlistId), trackIds)
        val ctx = CommandContext(uid, ProtocolId("JELLYFIN"), clock.now(), IdempotencyKey(UUID.randomUUID().toString()), playlist.version)
        playlistUseCases.execute(cmd, ctx)
        return ResponseEntity.noContent().build()
    }

    @DeleteMapping("/Items/{playlistId}")
    fun deletePlaylist(
        @PathVariable playlistId: String,
        principal: Principal,
    ): ResponseEntity<Void> {
        if (!isValidUuid(playlistId)) return ResponseEntity.badRequest().build()
        val uid = UserId(principal.name)
        val playlist = playlistQueries.find(PlaylistId(playlistId)) ?: return ResponseEntity.notFound().build()
        val cmd = DeletePlaylist(PlaylistId(playlistId))
        val ctx = CommandContext(uid, ProtocolId("JELLYFIN"), clock.now(), IdempotencyKey(UUID.randomUUID().toString()), playlist.version)
        playlistUseCases.execute(cmd, ctx)
        return ResponseEntity.noContent().build()
    }

    @PostMapping("/Playlists/{playlistId}/Items/{itemId}/Move/{newIndex}")
    fun moveItem(
        @PathVariable playlistId: String,
        @PathVariable itemId: String,
        @PathVariable newIndex: Int,
        principal: Principal,
    ): ResponseEntity<Void> {
        if (!isValidUuid(playlistId)) return ResponseEntity.badRequest().build()
        val uid = UserId(principal.name)
        val playlist = playlistQueries.find(PlaylistId(playlistId)) ?: return ResponseEntity.notFound().build()
        val currentOrder = playlist.tracks.map { it.trackId }
        val trackToMove = currentOrder.find { it.value == itemId } ?: return ResponseEntity.notFound().build()
        val withoutMoved = currentOrder.filter { it.value != itemId }
        val reordered = withoutMoved.toMutableList().apply { add(newIndex.coerceIn(0, size), trackToMove) }
        val cmd = ReorderPlaylistTracks(PlaylistId(playlistId), reordered)
        val ctx = CommandContext(uid, ProtocolId("JELLYFIN"), clock.now(), IdempotencyKey(UUID.randomUUID().toString()), playlist.version)
        playlistUseCases.execute(cmd, ctx)
        return ResponseEntity.noContent().build()
    }
}
