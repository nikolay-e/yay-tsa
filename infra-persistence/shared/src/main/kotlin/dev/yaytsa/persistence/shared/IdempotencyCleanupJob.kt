package dev.yaytsa.persistence.shared

import dev.yaytsa.persistence.shared.jpa.IdempotencyRecordJpaRepository
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.temporal.ChronoUnit

@Component
class IdempotencyCleanupJob(
    private val jpa: IdempotencyRecordJpaRepository,
    private val clock: dev.yaytsa.application.shared.port.Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelay = 3_600_000)
    @Transactional
    fun cleanup() {
        val cutoff = clock.now().minus(24, ChronoUnit.HOURS)
        val deleted = jpa.deleteByCreatedAtBefore(cutoff)
        if (deleted > 0) {
            log.info("Cleaned up {} expired idempotency records", deleted)
        }
    }
}
