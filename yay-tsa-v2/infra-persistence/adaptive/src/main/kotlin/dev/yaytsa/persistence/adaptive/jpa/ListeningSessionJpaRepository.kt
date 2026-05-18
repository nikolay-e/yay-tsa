package dev.yaytsa.persistence.adaptive.jpa

import dev.yaytsa.persistence.adaptive.entity.ListeningSessionEntity
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface ListeningSessionJpaRepository : JpaRepository<ListeningSessionEntity, UUID> {
    // v1-ETL + early bugs can leave multiple ACTIVE rows per user; return the most
    // recent so /v1/sessions/active doesn't blow up with IncorrectResultSizeDataAccessException.
    fun findFirstByUserIdAndEndedAtIsNullOrderByStartedAtDesc(userId: UUID): ListeningSessionEntity?

    fun findByEndedAtIsNull(): List<ListeningSessionEntity>
}
