package dev.yaytsa.persistence.library.repository

import dev.yaytsa.persistence.library.entity.AlbumJpa
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface AlbumRepository : JpaRepository<AlbumJpa, UUID> {
    fun findByArtistId(artistId: UUID): List<AlbumJpa>
}
