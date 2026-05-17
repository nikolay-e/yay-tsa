package dev.yaytsa.persistence.library.repository

import dev.yaytsa.persistence.library.entity.LibraryEntityJpa
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

interface LibraryEntityRepository : JpaRepository<LibraryEntityJpa, UUID> {
    @Query(
        "SELECT e FROM LibraryEntityJpa e WHERE e.entityType = :entityType " +
            "ORDER BY COALESCE(e.sortName, e.name)",
    )
    fun findByEntityTypeOrderBySortName(entityType: String): List<LibraryEntityJpa>

    @Query(
        "SELECT e FROM LibraryEntityJpa e WHERE e.entityType = :entityType " +
            "ORDER BY COALESCE(e.sortName, e.name)",
    )
    fun findByEntityTypeOrderBySortNamePaged(
        entityType: String,
        org: org.springframework.data.domain.Pageable,
    ): List<LibraryEntityJpa>

    @Query(
        value =
            "SELECT * FROM core_v2_library.entities e WHERE e.name ILIKE :pattern " +
                "AND e.entity_type = :entityType ORDER BY COALESCE(e.sort_name, e.name)",
        nativeQuery = true,
    )
    fun searchByNameAndType(
        pattern: String,
        entityType: String,
        pageable: org.springframework.data.domain.Pageable,
    ): List<LibraryEntityJpa>

    fun findAllByIdIn(ids: Collection<UUID>): List<LibraryEntityJpa>

    fun findBySourcePath(sourcePath: String): LibraryEntityJpa?

    @Query(
        value =
            "SELECT * FROM core_v2_library.entities e " +
                "WHERE e.entity_type = :entityType " +
                "AND lower(e.source_path COLLATE \"und-x-icu\") = lower(:sourcePath COLLATE \"und-x-icu\") " +
                "LIMIT 1",
        nativeQuery = true,
    )
    fun findBySourcePathCaseInsensitive(
        sourcePath: String,
        entityType: String,
    ): LibraryEntityJpa?

    @Query(
        value =
            "SELECT * FROM core_v2_library.entities e " +
                "WHERE e.entity_type = 'TRACK' AND e.source_path LIKE :suffix " +
                "ORDER BY length(e.source_path) ASC LIMIT 1",
        nativeQuery = true,
    )
    fun findTrackBySourcePathSuffix(suffix: String): LibraryEntityJpa?

    fun countByEntityType(entityType: String): Long

    @Query(
        value =
            "SELECT count(*) FROM core_v2_library.entities e " +
                "WHERE e.name ILIKE :pattern AND e.entity_type = :entityType",
        nativeQuery = true,
    )
    fun countByNameAndType(
        pattern: String,
        entityType: String,
    ): Long

    @Modifying
    @Transactional
    @Query(
        value =
            """
            DELETE FROM core_v2_library.entities e
            WHERE e.entity_type = 'ARTIST'
              AND NOT EXISTS (SELECT 1 FROM core_v2_library.albums a WHERE a.artist_id = e.id)
              AND NOT EXISTS (SELECT 1 FROM core_v2_library.audio_tracks t WHERE t.album_artist_id = e.id)
            """,
        nativeQuery = true,
    )
    fun deleteOrphanArtists(): Int
}
