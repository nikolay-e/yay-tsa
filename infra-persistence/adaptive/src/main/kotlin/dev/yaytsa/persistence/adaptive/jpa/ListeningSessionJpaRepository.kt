package dev.yaytsa.persistence.adaptive.jpa

import dev.yaytsa.persistence.adaptive.entity.ListeningSessionEntity
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface ListeningSessionJpaRepository : JpaRepository<ListeningSessionEntity, UUID> {
    fun findByUserIdAndEndedAtIsNull(userId: UUID): ListeningSessionEntity?

    fun findByEndedAtIsNull(): List<ListeningSessionEntity>
}
