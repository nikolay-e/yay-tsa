package dev.yaytsa.persistence.library.repository

import dev.yaytsa.persistence.library.entity.ImageJpa
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface ImageRepository : JpaRepository<ImageJpa, UUID> {
    fun findByEntityIdAndIsPrimaryTrue(entityId: UUID): ImageJpa?

    fun findByEntityId(entityId: UUID): List<ImageJpa>

    fun findByEntityIdInAndIsPrimaryTrue(entityIds: Collection<UUID>): List<ImageJpa>
}
