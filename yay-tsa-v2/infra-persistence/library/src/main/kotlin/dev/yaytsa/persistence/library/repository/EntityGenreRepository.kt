package dev.yaytsa.persistence.library.repository

import dev.yaytsa.persistence.library.entity.EntityGenreId
import dev.yaytsa.persistence.library.entity.EntityGenreJpa
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import java.util.UUID

interface EntityGenreRepository : JpaRepository<EntityGenreJpa, EntityGenreId> {
    fun findByEntityId(entityId: UUID): List<EntityGenreJpa>

    fun findByEntityIdIn(entityIds: Collection<UUID>): List<EntityGenreJpa>

    @Query(
        value =
            "SELECT DISTINCT eg.entity_id FROM core_v2_library.entity_genres eg " +
                "JOIN core_v2_library.genres g ON g.id = eg.genre_id " +
                "JOIN core_v2_library.entities e ON e.id = eg.entity_id " +
                "WHERE e.entity_type = 'TRACK' AND lower(g.name) IN (:genreNames)",
        nativeQuery = true,
    )
    fun findTrackEntityIdsByGenreNames(genreNames: Collection<String>): List<UUID>

    // Audiobook tracks (genre=Audiobook) lacking a primary image row. Drives the enricher's
    // track/audiobook artwork pass: borrow the parent album/folder cover or fetch from a keyless
    // open source. Image-driven (no metadata_checked_at gate) so it self-heals when a source improves.
    @Query(
        value =
            "SELECT DISTINCT eg.entity_id FROM core_v2_library.entity_genres eg " +
                "JOIN core_v2_library.genres g ON g.id = eg.genre_id " +
                "JOIN core_v2_library.entities e ON e.id = eg.entity_id " +
                "WHERE e.entity_type = 'TRACK' AND lower(g.name) IN (:genreNames) " +
                "AND NOT EXISTS (" +
                "SELECT 1 FROM core_v2_library.images i WHERE i.entity_id = eg.entity_id AND i.is_primary) " +
                "ORDER BY eg.entity_id LIMIT :limit OFFSET :offset",
        nativeQuery = true,
    )
    fun findAudiobookTrackIdsWithoutPrimaryImage(
        genreNames: Collection<String>,
        limit: Int,
        offset: Int,
    ): List<UUID>
}
