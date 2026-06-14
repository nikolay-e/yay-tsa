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
}
