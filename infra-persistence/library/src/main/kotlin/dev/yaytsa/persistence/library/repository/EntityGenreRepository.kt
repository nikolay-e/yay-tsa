package dev.yaytsa.persistence.library.repository

import dev.yaytsa.persistence.library.entity.EntityGenreId
import dev.yaytsa.persistence.library.entity.EntityGenreJpa
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface EntityGenreRepository : JpaRepository<EntityGenreJpa, EntityGenreId> {
    fun findByEntityId(entityId: UUID): List<EntityGenreJpa>

    fun findByEntityIdIn(entityIds: Collection<UUID>): List<EntityGenreJpa>
}
