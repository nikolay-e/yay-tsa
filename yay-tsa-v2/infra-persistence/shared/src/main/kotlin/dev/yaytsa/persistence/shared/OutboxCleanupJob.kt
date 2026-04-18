package dev.yaytsa.persistence.shared

import dev.yaytsa.persistence.shared.jpa.OutboxJpaRepository
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.temporal.ChronoUnit

@Component
class OutboxCleanupJob(
    private val jpa: OutboxJpaRepository,
    private val clock: dev.yaytsa.application.shared.port.Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelay = 3_600_000)
    @Transactional
    fun cleanup() {
        val cutoff = clock.now().minus(24, ChronoUnit.HOURS)
        val deleted = jpa.deleteByPublishedAtNotNullAndPublishedAtBefore(cutoff)
        if (deleted > 0) {
            log.info("Cleaned up {} published outbox entries", deleted)
        }
    }
}
