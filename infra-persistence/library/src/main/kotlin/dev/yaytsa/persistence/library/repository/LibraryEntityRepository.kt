package dev.yaytsa.persistence.library.repository

import dev.yaytsa.persistence.library.entity.LibraryEntityJpa
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
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
}
