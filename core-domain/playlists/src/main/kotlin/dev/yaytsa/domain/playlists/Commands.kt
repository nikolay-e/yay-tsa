package dev.yaytsa.domain.playlists

import dev.yaytsa.shared.Command
import dev.yaytsa.shared.TrackId
import dev.yaytsa.shared.UserId
import java.time.Instant

sealed interface PlaylistCommand : Command {
    val playlistId: PlaylistId
}

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
) : PlaylistCommand

data class UpdatePlaylistDescription(
    override val playlistId: PlaylistId,
    val description: String?,
) : PlaylistCommand

data class SetPlaylistVisibility(
    override val playlistId: PlaylistId,
    val isPublic: Boolean,
) : PlaylistCommand

data class DeletePlaylist(
    override val playlistId: PlaylistId,
) : PlaylistCommand

data class AddTracksToPlaylist(
    override val playlistId: PlaylistId,
    val trackIds: List<TrackId>,
    val addedAt: Instant,
) : PlaylistCommand

data class RemoveTracksFromPlaylist(
    override val playlistId: PlaylistId,
    val trackIds: List<TrackId>,
) : PlaylistCommand

data class ReorderPlaylistTracks(
    override val playlistId: PlaylistId,
    val newOrder: List<TrackId>,
) : PlaylistCommand
