package dev.yaytsa.domain.playlists

import dev.yaytsa.shared.Command
import dev.yaytsa.shared.TrackId
import dev.yaytsa.shared.UserId
import java.time.Instant

sealed interface PlaylistCommand : Command {
    val playlistId: PlaylistId
}

sealed interface ExistingPlaylistCommand : PlaylistCommand

data class CreatePlaylist(
    override val playlistId: PlaylistId,
    val owner: UserId,
    val name: String,
    val description: String?,
    val isPublic: Boolean,
    val createdAt: Instant,
) : PlaylistCommand

data class RenamePlaylist(
    override val playlistId: PlaylistId,
    val newName: String,
) : ExistingPlaylistCommand

data class UpdatePlaylistDescription(
    override val playlistId: PlaylistId,
    val description: String?,
) : ExistingPlaylistCommand

data class SetPlaylistVisibility(
    override val playlistId: PlaylistId,
    val isPublic: Boolean,
) : ExistingPlaylistCommand

data class DeletePlaylist(
    override val playlistId: PlaylistId,
) : ExistingPlaylistCommand

data class AddTracksToPlaylist(
    override val playlistId: PlaylistId,
    val trackIds: List<TrackId>,
    val addedAt: Instant,
) : ExistingPlaylistCommand

data class RemoveTracksFromPlaylist(
    override val playlistId: PlaylistId,
    val trackIds: List<TrackId>,
) : ExistingPlaylistCommand

data class RemovePlaylistEntriesByPosition(
    override val playlistId: PlaylistId,
    val positions: List<Int>,
) : ExistingPlaylistCommand

data class ReorderPlaylistTracks(
    override val playlistId: PlaylistId,
    val newOrder: List<TrackId>,
) : ExistingPlaylistCommand
