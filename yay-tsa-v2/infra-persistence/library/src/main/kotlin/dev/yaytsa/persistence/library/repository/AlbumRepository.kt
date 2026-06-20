package dev.yaytsa.persistence.library.repository

import dev.yaytsa.persistence.library.entity.AlbumJpa
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.util.UUID

interface AlbumRepository : JpaRepository<AlbumJpa, UUID> {
    fun findByArtistId(artistId: UUID): List<AlbumJpa>

    @Query(
        value =
            "SELECT * FROM core_v2_library.albums " +
                "WHERE metadata_checked_at IS NULL ORDER BY entity_id LIMIT :limit OFFSET :offset",
        nativeQuery = true,
    )
    fun findByMetadataCheckedAtIsNull(
        limit: Int,
        offset: Int,
    ): List<AlbumJpa>

    // Image-driven artwork backfill: albums with no primary image row, regardless of whether
    // metadata_checked_at is already stamped. Self-healing — when a cover source improves, art-less
    // albums are reconsidered on the next cycle without a manual metadata_checked_at reset.
    @Query(
        value =
            "SELECT a.* FROM core_v2_library.albums a " +
                "WHERE NOT EXISTS (" +
                "SELECT 1 FROM core_v2_library.images i WHERE i.entity_id = a.entity_id AND i.is_primary) " +
                "ORDER BY a.entity_id LIMIT :limit OFFSET :offset",
        nativeQuery = true,
    )
    fun findWithoutPrimaryImage(
        limit: Int,
        offset: Int,
    ): List<AlbumJpa>

    fun findByReleaseDateBetween(
        from: java.time.LocalDate,
        to: java.time.LocalDate,
        pageable: org.springframework.data.domain.Pageable,
    ): List<AlbumJpa>

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
