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

    // Honors the Pageable's Sort (unlike the *OrderBySortName* variants which hardcode it),
    // so callers can sort by sortName / createdAt in either direction.
    fun findByEntityType(
        entityType: String,
        pageable: org.springframework.data.domain.Pageable,
    ): List<LibraryEntityJpa>

    @Query(
        value =
            "SELECT * FROM core_v2_library.entities e WHERE e.name ILIKE :pattern ESCAPE '\\' " +
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
        value = "SELECT * FROM core_v2_library.entities WHERE entity_type = :entityType ORDER BY random() LIMIT :limit",
        nativeQuery = true,
    )
    fun findRandomByEntityType(
        entityType: String,
        limit: Int,
    ): List<LibraryEntityJpa>

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
                "WHERE e.name ILIKE :pattern ESCAPE '\\' AND e.entity_type = :entityType",
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

    @Query(
        value =
            "SELECT id FROM core_v2_library.entities " +
                "WHERE entity_type = 'TRACK' AND library_root = :libraryRoot",
        nativeQuery = true,
    )
    fun findTrackIdsByLibraryRoot(libraryRoot: String): List<UUID>

    @Query(
        value =
            "SELECT id, source_path FROM core_v2_library.entities " +
                "WHERE entity_type = 'TRACK' AND library_root = :libraryRoot",
        nativeQuery = true,
    )
    fun findTrackIdSourcePathsByLibraryRoot(libraryRoot: String): List<Array<Any?>>

    @Modifying
    @Transactional
    @Query(
        value =
            """
            DELETE FROM core_v2_library.audio_tracks t
            WHERE t.entity_id IN (:ids)
            """,
        nativeQuery = true,
    )
    fun deleteAudioTracksByEntityIds(ids: Collection<UUID>): Int

    @Modifying
    @Transactional
    @Query(
        value =
            """
            DELETE FROM core_v2_library.entity_genres g
            WHERE g.entity_id IN (:ids)
            """,
        nativeQuery = true,
    )
    fun deleteEntityGenresByEntityIds(ids: Collection<UUID>): Int

    @Modifying
    @Transactional
    @Query(
        value =
            """
            DELETE FROM core_v2_library.images i
            WHERE i.entity_id IN (:ids)
            """,
        nativeQuery = true,
    )
    fun deleteImagesByEntityIds(ids: Collection<UUID>): Int

    @Modifying
    @Transactional
    @Query(
        value =
            """
            DELETE FROM core_v2_library.entities e
            WHERE e.id IN (:ids)
            """,
        nativeQuery = true,
    )
    fun deleteEntitiesByIds(ids: Collection<UUID>): Int

    @Modifying
    @Transactional
    @Query(
        value =
            """
            DELETE FROM core_v2_library.albums a
            WHERE NOT EXISTS (SELECT 1 FROM core_v2_library.audio_tracks t WHERE t.album_id = a.entity_id)
            """,
        nativeQuery = true,
    )
    fun deleteAlbumRowsWithoutTracks(): Int

    @Modifying
    @Transactional
    @Query(
        value =
            """
            DELETE FROM core_v2_library.entities e
            WHERE e.entity_type = 'ALBUM'
              AND NOT EXISTS (SELECT 1 FROM core_v2_library.audio_tracks t WHERE t.album_id = e.id)
            """,
        nativeQuery = true,
    )
    fun deleteOrphanAlbums(): Int
}
