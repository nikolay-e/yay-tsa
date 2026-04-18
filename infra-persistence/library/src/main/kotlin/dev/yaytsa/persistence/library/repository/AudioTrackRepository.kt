package dev.yaytsa.persistence.library.repository

import dev.yaytsa.persistence.library.entity.AudioTrackJpa
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface AudioTrackRepository : JpaRepository<AudioTrackJpa, UUID> {
    fun findByAlbumId(albumId: UUID): List<AudioTrackJpa>

    fun findAllByEntityIdIn(entityIds: Collection<UUID>): List<AudioTrackJpa>
}
