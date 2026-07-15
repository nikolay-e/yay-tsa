package dev.yaytsa.adapteropensubsonic

import dev.yaytsa.adaptershared.AdapterCommandContextFactory
import dev.yaytsa.application.library.LibraryQueries
import dev.yaytsa.application.playlists.PlaylistQueries
import dev.yaytsa.application.playlists.PlaylistUseCases
import dev.yaytsa.domain.playlists.AddTracksToPlaylist
import dev.yaytsa.domain.playlists.CreatePlaylist
import dev.yaytsa.domain.playlists.DeletePlaylist
import dev.yaytsa.domain.playlists.PlaylistAggregate
import dev.yaytsa.domain.playlists.PlaylistCommand
import dev.yaytsa.domain.playlists.PlaylistId
import dev.yaytsa.domain.playlists.RemovePlaylistEntriesByPosition
import dev.yaytsa.domain.playlists.RenamePlaylist
import dev.yaytsa.domain.playlists.SetPlaylistVisibility
import dev.yaytsa.domain.playlists.UpdatePlaylistDescription
import dev.yaytsa.shared.AggregateVersion
import dev.yaytsa.shared.CommandContext
import dev.yaytsa.shared.CommandResult
import dev.yaytsa.shared.EntityId
import dev.yaytsa.shared.TrackId
import dev.yaytsa.shared.UserId
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.security.Principal
import java.util.UUID

@RestController
@RequestMapping("/rest")
class SubsonicPlaylistsController(
    private val playlistQueries: PlaylistQueries,
    private val playlistUseCases: PlaylistUseCases,
    private val libraryQueries: LibraryQueries,
    @Qualifier("subsonicCommandContextFactory")
    private val ctxFactory: AdapterCommandContextFactory,
    private val support: SubsonicEndpointSupport,
) {
    @GetMapping("/getPlaylists", "/getPlaylists.view")
    fun getPlaylists(
        @RequestParam(required = false) f: String?,
        principal: Principal,
    ): ResponseEntity<String> {
        val playlists = playlistQueries.findByOwner(UserId(principal.name))
        return support.write(
            ok {
                copy(
                    playlists =
                        PlaylistsWrapper(
                            playlists.map {
                                PlaylistElement(it.id.value, it.name, it.tracks.size, it.isPublic, it.owner.value)
                            },
                        ),
                )
            },
            f,
        )
    }

    private fun playlistDetail(playlist: PlaylistAggregate): PlaylistDetail {
        val entries = support.toChildren(libraryQueries.getTracksByIds(playlist.tracks.map { EntityId(it.trackId.value) }))
        return PlaylistDetail(
            playlist.id.value,
            playlist.name,
            entries.size,
            entries,
            playlist.isPublic,
            playlist.owner.value,
        )
    }

    // BOLA guard (OWASP API1:2023): playlistQueries.find(id) is a bare read that never goes
    // through PlaylistHandler's `snapshot.owner != ctx.userId` check (that check only fires
    // for mutating commands). Every read path that can return a playlist's name/track-list/
    // metadata to the wire must call this first, or any authenticated user can read any other
    // user's private playlist by guessing/enumerating its UUID.
    private fun visibleTo(
        playlist: PlaylistAggregate,
        principal: Principal,
    ): Boolean = playlist.isPublic || playlist.owner.value == principal.name

    @GetMapping("/getPlaylist", "/getPlaylist.view")
    fun getPlaylist(
        @RequestParam id: String,
        @RequestParam(required = false) f: String?,
        principal: Principal,
    ): ResponseEntity<String> {
        val playlist = playlistQueries.find(PlaylistId(id)) ?: return support.notFound("Playlist", id, f)
        if (!visibleTo(playlist, principal)) return support.notFound("Playlist", id, f)
        return support.write(ok { copy(playlist = playlistDetail(playlist)) }, f)
    }

    @GetMapping("/createPlaylist", "/createPlaylist.view")
    fun createPlaylist(
        @RequestParam(required = false) playlistId: String?,
        @RequestParam(required = false) name: String?,
        @RequestParam(required = false) songId: List<String>?,
        @RequestParam(required = false) f: String?,
        principal: Principal,
    ): ResponseEntity<String> {
        val userId = UserId(principal.name)
        val targetId =
            if (playlistId != null) {
                redefinePlaylistContent(userId, PlaylistId(playlistId), songId.orEmpty())
            } else {
                createNewPlaylist(userId, name, songId.orEmpty())
            }
        val created = playlistQueries.find(targetId) ?: return support.notFound("Playlist", targetId.value, f)
        if (!visibleTo(created, principal)) return support.notFound("Playlist", targetId.value, f)
        return support.write(ok { copy(playlist = playlistDetail(created)) }, f)
    }

    private fun redefinePlaylistContent(
        userId: UserId,
        playlistId: PlaylistId,
        songIds: List<String>,
    ): PlaylistId {
        val playlist =
            playlistQueries.find(playlistId)
                ?: throw SubsonicApiException(70, "Playlist not found")
        var version = playlist.version
        if (playlist.tracks.isNotEmpty()) {
            version = executePlaylistCommand(userId, version) { RemovePlaylistEntriesByPosition(playlistId, playlist.tracks.indices.toList()) }
        }
        if (songIds.isNotEmpty()) {
            executePlaylistCommand(userId, version) { ctx -> AddTracksToPlaylist(playlistId, songIds.map { TrackId(it) }, ctx.requestTime) }
        }
        return playlistId
    }

    private fun createNewPlaylist(
        userId: UserId,
        name: String?,
        songIds: List<String>,
    ): PlaylistId {
        if (name == null) throw SubsonicApiException(10, "Required parameter is missing: name")
        val newId = PlaylistId(UUID.randomUUID().toString())
        val version =
            executePlaylistCommand(userId, AggregateVersion.INITIAL) { ctx ->
                CreatePlaylist(newId, userId, name, null, false, ctx.requestTime)
            }
        if (songIds.isNotEmpty()) {
            executePlaylistCommand(userId, version) { ctx -> AddTracksToPlaylist(newId, songIds.map { TrackId(it) }, ctx.requestTime) }
        }
        return newId
    }

    private fun executePlaylistCommand(
        userId: UserId,
        expectedVersion: AggregateVersion,
        command: (CommandContext) -> PlaylistCommand,
    ): AggregateVersion {
        val ctx = ctxFactory.create(userId, expectedVersion)
        return when (val result = playlistUseCases.execute(command(ctx), ctx)) {
            is CommandResult.Success -> result.newVersion
            is CommandResult.Failed -> support.failWith(result.failure)
        }
    }

    @GetMapping("/updatePlaylist", "/updatePlaylist.view")
    fun updatePlaylist(
        @RequestParam playlistId: String,
        @RequestParam(required = false) name: String?,
        @RequestParam(required = false) comment: String?,
        @RequestParam(name = "public", required = false) isPublic: Boolean?,
        @RequestParam(required = false) songIdToAdd: List<String>?,
        @RequestParam(required = false) songIndexToRemove: List<Int>?,
        @RequestParam(required = false) f: String?,
        principal: Principal,
    ): ResponseEntity<String> {
        val pid = PlaylistId(playlistId)
        val playlist = playlistQueries.find(pid) ?: return support.notFound("Playlist", playlistId, f)
        val userId = UserId(principal.name)
        val steps =
            buildList<(CommandContext) -> PlaylistCommand> {
                name?.let { newName -> add { RenamePlaylist(pid, newName) } }
                comment?.let { newComment -> add { UpdatePlaylistDescription(pid, newComment) } }
                isPublic?.let { visibility -> add { SetPlaylistVisibility(pid, visibility) } }
                songIndexToRemove?.takeIf { it.isNotEmpty() }?.let { positions -> add { RemovePlaylistEntriesByPosition(pid, positions) } }
                songIdToAdd?.takeIf { it.isNotEmpty() }?.let { ids ->
                    add { ctx -> AddTracksToPlaylist(pid, ids.map { TrackId(it) }, ctx.requestTime) }
                }
            }
        steps.fold(playlist.version) { version, step -> executePlaylistCommand(userId, version, step) }
        return support.write(ok(), f)
    }

    @GetMapping("/deletePlaylist", "/deletePlaylist.view")
    fun deletePlaylist(
        @RequestParam id: String,
        @RequestParam(required = false) f: String?,
        principal: Principal,
    ): ResponseEntity<String> {
        val playlist = playlistQueries.find(PlaylistId(id)) ?: return support.notFound("Playlist", id, f)
        val ctx = ctxFactory.create(UserId(principal.name), playlist.version)
        val result = playlistUseCases.execute(DeletePlaylist(PlaylistId(id)), ctx)
        return support.write(support.responseFor(result), f)
    }
}
