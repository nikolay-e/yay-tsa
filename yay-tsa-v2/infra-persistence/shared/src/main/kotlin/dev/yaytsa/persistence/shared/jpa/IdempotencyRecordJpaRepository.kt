package dev.yaytsa.persistence.shared.jpa

import dev.yaytsa.persistence.shared.entity.IdempotencyRecordEntity
import dev.yaytsa.persistence.shared.entity.IdempotencyRecordEntityId
import org.springframework.data.jpa.repository.JpaRepository
import java.time.Instant

interface IdempotencyRecordJpaRepository : JpaRepository<IdempotencyRecordEntity, IdempotencyRecordEntityId> {
    fun findByUserIdAndCommandTypeAndIdemKey(
        userId: String,
        commandType: String,
        idemKey: String,
    ): IdempotencyRecordEntity?

    fun deleteByCreatedAtBefore(cutoff: Instant): Int
}
