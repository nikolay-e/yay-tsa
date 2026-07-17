package dev.yaytsa.adapterjellyfin

import com.fasterxml.jackson.annotation.JsonAlias
import com.fasterxml.jackson.annotation.JsonProperty
import dev.yaytsa.adaptershared.AdapterCommandContextFactory
import dev.yaytsa.adaptershared.BaseItem
import dev.yaytsa.adaptershared.HttpFailureTranslator
import dev.yaytsa.adaptershared.UserItemData
import dev.yaytsa.adaptershared.msToTicks
import dev.yaytsa.application.library.LibraryQueries
import dev.yaytsa.application.playlists.PlaylistQueries
import dev.yaytsa.application.playlists.PlaylistUseCases
import dev.yaytsa.application.preferences.PreferencesQueries
import dev.yaytsa.application.shared.port.Clock
import dev.yaytsa.domain.playlists.AddTracksToPlaylist
import dev.yaytsa.domain.playlists.CreatePlaylist
import dev.yaytsa.domain.playlists.DeletePlaylist
import dev.yaytsa.domain.playlists.PlaylistId
import dev.yaytsa.domain.playlists.RemovePlaylistEntriesByPosition
import dev.yaytsa.domain.playlists.RenamePlaylist
import dev.yaytsa.domain.playlists.ReorderPlaylistTracks
import dev.yaytsa.shared.AggregateVersion
import dev.yaytsa.shared.CommandResult
import dev.yaytsa.shared.EntityId
import dev.yaytsa.shared.Failure
import dev.yaytsa.shared.TrackId
import dev.yaytsa.shared.UserId
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
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
    @Qualifier("jellyfinCommandContextFactory")
    private val ctxFactory: AdapterCommandContextFactory,
    private val failureTranslator: HttpFailureTranslator,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    private fun statusFromFailure(failure: Failure): ResponseEntity<Void> = failureTranslator.empty(failure)

    private fun isValidUuid(value: String): Boolean =
        try {
            UUID.fromString(value)
            true
        } catch (_: IllegalArgumentException) {
            false
        }

    data class CreatePlaylistRequest(
        @JsonProperty("Name") @JsonAlias("name") val name: String,
        @JsonProperty("UserId") @JsonAlias("userId") val userId: String? = null,
        @JsonProperty("Ids") @JsonAlias("ids") val ids: List<String>? = null,
        @JsonProperty("IsPublic") @JsonAlias("isPublic") val isPublic: Boolean? = null,
    )

    @PostMapping("/Playlists")
    fun createPlaylist(
        @RequestBody request: CreatePlaylistRequest,
        principal: Principal,
    ): ResponseEntity<Any> {
        val requestedOwner = request.userId?.takeIf { it.isNotBlank() }
        if (requestedOwner != null && requestedOwner != principal.name) {
            return ResponseEntity.status(org.springframework.http.HttpStatus.FORBIDDEN).build()
        }
        require(request.name.isNotBlank()) { "Name is required" }
        val uid = UserId(principal.name)
        val pid = PlaylistId(UUID.randomUUID().toString())
        val now = clock.now()
        val createCmd = CreatePlaylist(pid, uid, request.name, null, request.isPublic ?: false, now)
        val createCtx = ctxFactory.create(uid, AggregateVersion.INITIAL)
        val createResult = playlistUseCases.execute(createCmd, createCtx)
        if (createResult is CommandResult.Failed) {
            log.warn("CreatePlaylist failed for owner={}: {}", uid.value, createResult.failure)
            return failureTranslator.translate(createResult.failure)
        }
        createResult as CommandResult.Success

        val initialIds =
            request.ids
                ?.filter { it.isNotBlank() }
                ?.map { TrackId(it.trim()) }
                .orEmpty()
        if (initialIds.isNotEmpty()) {
            val addCmd = AddTracksToPlaylist(pid, initialIds, now)
            val addCtx = ctxFactory.create(uid, createResult.value.version)
            val addResult = playlistUseCases.execute(addCmd, addCtx)
            if (addResult is CommandResult.Failed) {
                log.warn("Initial AddTracksToPlaylist failed for playlist={}: {}", pid.value, addResult.failure)
                // Best-effort rollback so the caller doesn't see an empty playlist they didn't ask for.
                val deleteCmd = DeletePlaylist(pid)
                val deleteCtx = ctxFactory.create(uid, createResult.value.version)
                playlistUseCases.execute(deleteCmd, deleteCtx)
                return failureTranslator.translate(addResult.failure)
            }
        }

        return ResponseEntity.ok(mapOf("Id" to pid.value))
    }

    data class UpdatePlaylistRequest(
        @JsonProperty("Name") @JsonAlias("name") val name: String? = null,
    )

    @PostMapping("/Playlists/{playlistId}")
    fun updatePlaylist(
        @PathVariable playlistId: String,
        @RequestBody request: UpdatePlaylistRequest,
        principal: Principal,
    ): ResponseEntity<Void> {
        if (!isValidUuid(playlistId)) return ResponseEntity.badRequest().build()
        val newName = request.name ?: return ResponseEntity.badRequest().build()
        val uid = UserId(principal.name)
        val playlist = playlistQueries.find(PlaylistId(playlistId)) ?: return ResponseEntity.notFound().build()
        val cmd = RenamePlaylist(PlaylistId(playlistId), newName)
        val ctx = ctxFactory.create(uid, playlist.version)
        return when (val result = playlistUseCases.execute(cmd, ctx)) {
            is CommandResult.Success -> ResponseEntity.noContent().build()
            is CommandResult.Failed -> {
                log.warn("RenamePlaylist failed for playlist={}: {}", playlistId, result.failure)
                statusFromFailure(result.failure)
            }
        }
    }

    @GetMapping("/Playlists/{playlistId}/Items")
    fun getPlaylistItems(
        @PathVariable playlistId: String,
        @RequestParam(name = "UserId", required = false) userId: String?,
        @RequestParam(name = "StartIndex", required = false, defaultValue = "0") startIndex: Int,
        @RequestParam(name = "Limit", required = false, defaultValue = "100") limitParam: Int,
        principal: Principal?,
    ): ResponseEntity<Any> {
        if (!isValidUuid(playlistId)) return ResponseEntity.badRequest().build()
        if (userId != null && userId != principal?.name) return ResponseEntity.status(org.springframework.http.HttpStatus.FORBIDDEN).build()
        val playlist =
            playlistQueries.find(PlaylistId(playlistId))
                ?: return ResponseEntity.notFound().build()
        // BOLA guard (OWASP API1:2023): this is a query, so it never goes through
        // PlaylistHandler's `snapshot.owner != ctx.userId` check that every mutating
        // command gets. Without this, any authenticated user could read any other
        // user's private playlist tracks just by knowing/guessing the playlist UUID.
        // 404 (not 403) so a private playlist's existence isn't confirmed to non-owners.
        if (!playlist.isPublic && playlist.owner.value != principal?.name) {
            return ResponseEntity.notFound().build()
        }

        val uid = principal?.name
        val favTrackIds =
            if (uid != null) {
                preferencesQueries.findFavoriteTrackIds(UserId(uid))
            } else {
                emptySet()
            }

        val items =
            playlist.tracks
                .drop(startIndex)
                .take(limitParam.coerceIn(1, LibraryQueries.MAX_PAGE_SIZE))
                .mapIndexedNotNull { relativeIndex, entry ->
                    libraryQueries.getTrack(EntityId(entry.trackId.value))?.let { track ->
                        BaseItem(
                            id = track.id.value,
                            name = track.name,
                            type = "Audio",
                            runTimeTicks = msToTicks(track.durationMs),
                            playlistItemId = (startIndex + relativeIndex).toString(),
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
        // BOLA guard (OWASP API1:2023): the UserId param feeds the command context that
        // PlaylistHandler's owner check runs against, so accepting an arbitrary value would
        // let any authenticated user impersonate the owner and write into their playlist.
        if (userId != null && userId != principal.name) {
            return ResponseEntity.status(org.springframework.http.HttpStatus.FORBIDDEN).build()
        }
        val uid = UserId(principal.name)
        val playlist = playlistQueries.find(PlaylistId(playlistId)) ?: return ResponseEntity.notFound().build()
        val trackIds = ids.split(",").map { TrackId(it.trim()) }
        val cmd = AddTracksToPlaylist(PlaylistId(playlistId), trackIds, clock.now())
        val ctx = ctxFactory.create(uid, playlist.version)
        return when (val result = playlistUseCases.execute(cmd, ctx)) {
            is CommandResult.Success -> ResponseEntity.noContent().build()
            is CommandResult.Failed -> {
                log.warn("AddTracksToPlaylist failed for playlist={} tracks={}: {}", playlistId, trackIds.map { it.value }, result.failure)
                statusFromFailure(result.failure)
            }
        }
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
        val positions =
            entryIds.split(",").map { it.trim() }.map { token ->
                token.toIntOrNull() ?: return ResponseEntity.badRequest().build()
            }
        val cmd = RemovePlaylistEntriesByPosition(PlaylistId(playlistId), positions)
        val ctx = ctxFactory.create(uid, playlist.version)
        return when (val result = playlistUseCases.execute(cmd, ctx)) {
            is CommandResult.Success -> ResponseEntity.noContent().build()
            is CommandResult.Failed -> {
                log.warn("RemovePlaylistEntriesByPosition failed for playlist={}: {}", playlistId, result.failure)
                statusFromFailure(result.failure)
            }
        }
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
        val ctx = ctxFactory.create(uid, playlist.version)
        return when (val result = playlistUseCases.execute(cmd, ctx)) {
            is CommandResult.Success -> ResponseEntity.noContent().build()
            is CommandResult.Failed -> {
                log.warn("DeletePlaylist failed for playlist={}: {}", playlistId, result.failure)
                statusFromFailure(result.failure)
            }
        }
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
        // The wire entry id is the playlist position: getPlaylistItems emits PlaylistItemId as the
        // index and removeFromPlaylist parses EntryIds as positions. A track-id match here could
        // never see those values, so every protocol-conformant Move returned 404. Track ids are
        // still accepted for clients that pass the item id directly.
        val fromIndex =
            itemId.toIntOrNull()?.takeIf { it in currentOrder.indices }
                ?: currentOrder.indexOfFirst { it.value == itemId }.takeIf { it >= 0 }
                ?: return ResponseEntity.notFound().build()
        val reordered =
            currentOrder.toMutableList().apply {
                val moved = removeAt(fromIndex)
                add(newIndex.coerceIn(0, size), moved)
            }
        val cmd = ReorderPlaylistTracks(PlaylistId(playlistId), reordered)
        val ctx = ctxFactory.create(uid, playlist.version)
        return when (val result = playlistUseCases.execute(cmd, ctx)) {
            is CommandResult.Success -> ResponseEntity.noContent().build()
            is CommandResult.Failed -> {
                log.warn("ReorderPlaylistTracks failed for playlist={}: {}", playlistId, result.failure)
                statusFromFailure(result.failure)
            }
        }
    }
}
