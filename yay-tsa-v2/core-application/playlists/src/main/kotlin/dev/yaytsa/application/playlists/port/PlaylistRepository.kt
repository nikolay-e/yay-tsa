package dev.yaytsa.application.playlists.port

import dev.yaytsa.domain.playlists.PlaylistAggregate
import dev.yaytsa.domain.playlists.PlaylistId
import dev.yaytsa.shared.UserId

interface PlaylistRepository {
    fun find(playlistId: PlaylistId): PlaylistAggregate?

    fun save(aggregate: PlaylistAggregate)

    fun delete(playlistId: PlaylistId)

    fun findByOwner(userId: UserId): List<PlaylistAggregate>
}
