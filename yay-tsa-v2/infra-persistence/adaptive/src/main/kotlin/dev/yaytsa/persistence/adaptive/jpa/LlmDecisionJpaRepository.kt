package dev.yaytsa.persistence.adaptive.jpa

import dev.yaytsa.persistence.adaptive.entity.LlmDecisionEntity
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface LlmDecisionJpaRepository : JpaRepository<LlmDecisionEntity, UUID> {
    fun findBySessionIdOrderByCreatedAtDesc(
        sessionId: UUID,
        pageable: Pageable,
    ): List<LlmDecisionEntity>
}
