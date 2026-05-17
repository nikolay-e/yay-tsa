package dev.yaytsa.persistence.library.repository

import dev.yaytsa.persistence.library.entity.AlbumJpa
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.util.UUID

interface AlbumRepository : JpaRepository<AlbumJpa, UUID> {
    fun findByArtistId(artistId: UUID): List<AlbumJpa>

    @Query(
        "SELECT a.artistId AS artistId, COUNT(a) AS albumCount " +
            "FROM AlbumJpa a WHERE a.artistId IN :artistIds GROUP BY a.artistId",
    )
    fun countAlbumsByArtistIds(
        @Param("artistIds") artistIds: Collection<UUID>,
    ): List<AlbumCountByArtist>
}

interface AlbumCountByArtist {
    fun getArtistId(): UUID

    fun getAlbumCount(): Long
}
