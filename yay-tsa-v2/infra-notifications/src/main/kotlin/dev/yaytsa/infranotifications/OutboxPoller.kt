package dev.yaytsa.infranotifications

import dev.yaytsa.persistence.shared.jpa.OutboxJpaRepository
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

@Component
class OutboxPoller(
    private val outboxJpa: OutboxJpaRepository,
    private val publisher: NotificationPublisher,
    private val clock: dev.yaytsa.application.shared.port.Clock,
) {
    @Scheduled(fixedDelay = 1_000)
    @Transactional
    fun poll() {
        val entries = outboxJpa.findUnpublishedForUpdate(BATCH_SIZE)
        for (entry in entries) {
            publisher.publish(entry.context, entry.payload)
            entry.publishedAt = clock.now()
            outboxJpa.save(entry)
        }
    }

    companion object {
        private const val BATCH_SIZE = 50
    }
}
