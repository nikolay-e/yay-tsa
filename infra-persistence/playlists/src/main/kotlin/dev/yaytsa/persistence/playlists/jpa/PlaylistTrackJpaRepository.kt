package dev.yaytsa.persistence.playlists.jpa

import dev.yaytsa.persistence.playlists.entity.PlaylistTrackEntity
import dev.yaytsa.persistence.playlists.entity.PlaylistTrackEntityId
import org.springframework.data.jpa.repository.JpaRepository

interface PlaylistTrackJpaRepository : JpaRepository<PlaylistTrackEntity, PlaylistTrackEntityId> {
    fun findByPlaylistIdOrderByPosition(playlistId: String): List<PlaylistTrackEntity>

    fun deleteByPlaylistId(playlistId: String)
}
