package dev.yaytsa.persistence.adaptive.jpa

import dev.yaytsa.persistence.adaptive.entity.AdaptiveQueueEntryEntity
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface AdaptiveQueueEntryJpaRepository : JpaRepository<AdaptiveQueueEntryEntity, UUID> {
    fun findBySessionIdOrderByPositionAsc(sessionId: UUID): List<AdaptiveQueueEntryEntity>

    fun deleteBySessionId(sessionId: UUID)
}
