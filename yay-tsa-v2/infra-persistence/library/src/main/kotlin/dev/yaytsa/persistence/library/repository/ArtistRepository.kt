package dev.yaytsa.persistence.library.repository

import dev.yaytsa.persistence.library.entity.ArtistJpa
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import java.util.UUID

interface ArtistRepository : JpaRepository<ArtistJpa, UUID> {
    @Query(
        value =
            "SELECT * FROM core_v2_library.artists " +
                "WHERE metadata_checked_at IS NULL ORDER BY entity_id LIMIT :limit OFFSET :offset",
        nativeQuery = true,
    )
    fun findByMetadataCheckedAtIsNull(
        limit: Int,
        offset: Int,
    ): List<ArtistJpa>

    // Image-driven artwork backfill: artists with no primary image row (self-healing, see AlbumRepository).
    @Query(
        value =
            "SELECT a.* FROM core_v2_library.artists a " +
                "WHERE NOT EXISTS (" +
                "SELECT 1 FROM core_v2_library.images i WHERE i.entity_id = a.entity_id AND i.is_primary) " +
                "ORDER BY a.entity_id LIMIT :limit OFFSET :offset",
        nativeQuery = true,
    )
    fun findWithoutPrimaryImage(
        limit: Int,
        offset: Int,
    ): List<ArtistJpa>
}
