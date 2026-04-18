package dev.yaytsa.persistence.shared.jpa

import dev.yaytsa.persistence.shared.entity.OutboxEntity
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import java.time.Instant
import java.util.UUID

interface OutboxJpaRepository : JpaRepository<OutboxEntity, UUID> {
    fun findByPublishedAtIsNullOrderByCreatedAtAsc(limit: Pageable): List<OutboxEntity>

    @Query(
        value = """
            SELECT * FROM core_v2_shared.outbox
            WHERE published_at IS NULL
            ORDER BY created_at ASC
            LIMIT :limit
            FOR UPDATE SKIP LOCKED
        """,
        nativeQuery = true,
    )
    fun findUnpublishedForUpdate(limit: Int): List<OutboxEntity>

    fun deleteByPublishedAtNotNullAndPublishedAtBefore(cutoff: Instant): Int
}
