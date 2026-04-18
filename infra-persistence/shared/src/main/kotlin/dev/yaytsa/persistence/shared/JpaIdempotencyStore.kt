package dev.yaytsa.persistence.shared

import dev.yaytsa.application.shared.port.IdempotencyStore
import dev.yaytsa.application.shared.port.StoredIdempotencyRecord
import dev.yaytsa.persistence.shared.entity.IdempotencyRecordEntity
import dev.yaytsa.persistence.shared.jpa.IdempotencyRecordJpaRepository
import dev.yaytsa.shared.IdempotencyKey
import dev.yaytsa.shared.UserId
import org.springframework.stereotype.Repository

@Repository
class JpaIdempotencyStore(
    private val jpa: IdempotencyRecordJpaRepository,
    private val clock: dev.yaytsa.application.shared.port.Clock,
) : IdempotencyStore {
    override fun find(
        userId: UserId,
        commandType: String,
        key: IdempotencyKey,
    ): StoredIdempotencyRecord? {
        val entity =
            jpa.findByUserIdAndCommandTypeAndIdemKey(userId.value, commandType, sanitize(key.value))
                ?: return null
        return StoredIdempotencyRecord(payloadHash = entity.payloadHash, resultVersion = entity.resultVersion)
    }

    override fun store(
        userId: UserId,
        commandType: String,
        key: IdempotencyKey,
        payloadHash: String,
        resultVersion: Long,
    ) {
        val entity =
            IdempotencyRecordEntity(
                userId = userId.value,
                commandType = commandType,
                idemKey = sanitize(key.value),
                payloadHash = payloadHash,
                resultVersion = resultVersion,
                createdAt = clock.now(),
            )
        jpa.save(entity)
    }

    /** PostgreSQL rejects null bytes (0x00) in text columns; strip them at the persistence boundary. */
    private fun sanitize(value: String): String = value.replace("\u0000", "")
}
