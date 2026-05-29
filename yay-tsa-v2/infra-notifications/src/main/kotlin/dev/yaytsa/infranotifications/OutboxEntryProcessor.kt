package dev.yaytsa.infranotifications

import dev.yaytsa.persistence.shared.jpa.OutboxJpaRepository
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Component
class OutboxEntryProcessor(
    private val outboxJpa: OutboxJpaRepository,
    private val publisher: NotificationPublisher,
    private val clock: dev.yaytsa.application.shared.port.Clock,
) {
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun publishAndMark(id: UUID) {
        val entry = outboxJpa.findUnpublishedByIdForUpdate(id) ?: return
        publisher.publish(entry.context, entry.payload)
        entry.publishedAt = clock.now()
        outboxJpa.save(entry)
    }
}
