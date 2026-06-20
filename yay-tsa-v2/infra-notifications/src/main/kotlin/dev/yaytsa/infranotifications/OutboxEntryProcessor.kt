package dev.yaytsa.infranotifications

import dev.yaytsa.persistence.shared.jpa.OutboxJpaRepository
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Component
class OutboxEntryProcessor(
    private val outboxJpa: OutboxJpaRepository,
    private val publishers: List<NotificationPublisher>,
    private val clock: dev.yaytsa.application.shared.port.Clock,
) {
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun publishAndMark(id: UUID) {
        val entry = outboxJpa.findUnpublishedByIdForUpdate(id) ?: return
        // Fan out to every channel (e.g. WebSocket + device-SSE bridge). A failure in one
        // publisher must not drop the others or block marking the entry published.
        publishers.forEach { publisher ->
            runCatching { publisher.publish(entry.context, entry.payload) }
        }
        entry.publishedAt = clock.now()
        outboxJpa.save(entry)
    }
}
