package dev.yaytsa.persistence.playlists.jpa

import dev.yaytsa.persistence.playlists.entity.PlaylistEntity
import org.springframework.data.jpa.repository.JpaRepository

interface PlaylistJpaRepository : JpaRepository<PlaylistEntity, String> {
    fun findByOwner(owner: String): List<PlaylistEntity>
}
