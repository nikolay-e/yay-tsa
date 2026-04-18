package dev.yaytsa.application.playlists

import dev.yaytsa.application.playlists.port.PlaylistRepository
import dev.yaytsa.domain.playlists.PlaylistAggregate
import dev.yaytsa.domain.playlists.PlaylistId
import dev.yaytsa.shared.UserId

class PlaylistQueries(
    private val playlistRepo: PlaylistRepository,
) {
    fun find(playlistId: PlaylistId): PlaylistAggregate? = playlistRepo.find(playlistId)

    fun findByOwner(userId: UserId): List<PlaylistAggregate> = playlistRepo.findByOwner(userId)
}
