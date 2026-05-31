package dev.yaytsa.persistence.shared.jpa

import dev.yaytsa.persistence.shared.entity.IdempotencyRecordEntity
import dev.yaytsa.persistence.shared.entity.IdempotencyRecordEntityId
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

interface IdempotencyRecordJpaRepository : JpaRepository<IdempotencyRecordEntity, IdempotencyRecordEntityId> {
    fun findByUserIdAndCommandTypeAndIdemKey(
        userId: String,
        commandType: String,
        idemKey: String,
    ): IdempotencyRecordEntity?

    /**
     * Insert-first idempotency: ON CONFLICT DO NOTHING makes a concurrent duplicate a no-op
     * instead of a PK-violation that gets misreported as InvariantViolation. Returns affected
     * rows so the caller distinguishes "inserted" (1) from "already present" (0).
     */
    @Modifying
    @Transactional
    @Query(
        value = """
            INSERT INTO core_v2_shared.idempotency_records
                (user_id, command_type, idem_key, payload_hash, result_version, created_at)
            VALUES (:userId, :commandType, :idemKey, :payloadHash, :resultVersion, :createdAt)
            ON CONFLICT (user_id, command_type, idem_key) DO NOTHING
        """,
        nativeQuery = true,
    )
    fun insertIfAbsent(
        @Param("userId") userId: String,
        @Param("commandType") commandType: String,
        @Param("idemKey") idemKey: String,
        @Param("payloadHash") payloadHash: String,
        @Param("resultVersion") resultVersion: Long,
        @Param("createdAt") createdAt: Instant,
    ): Int

    fun deleteByCreatedAtBefore(cutoff: Instant): Int
}
