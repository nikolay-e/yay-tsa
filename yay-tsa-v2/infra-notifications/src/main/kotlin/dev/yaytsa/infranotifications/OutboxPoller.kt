package dev.yaytsa.infranotifications

import dev.yaytsa.persistence.shared.jpa.OutboxJpaRepository
import org.slf4j.LoggerFactory
import org.springframework.data.domain.PageRequest
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

@Component
class OutboxPoller(
    private val outboxJpa: OutboxJpaRepository,
    private val entryProcessor: OutboxEntryProcessor,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelay = 1_000)
    fun poll() {
        val candidates = outboxJpa.findByPublishedAtIsNullOrderByCreatedAtAscIdAsc(PageRequest.of(0, BATCH_SIZE))
        for (candidate in candidates) {
            try {
                entryProcessor.publishAndMark(candidate.id)
            } catch (ex: Exception) {
                log.warn("Failed to publish outbox entry {} [{}], will retry next tick", candidate.id, candidate.context, ex)
            }
        }
    }

    companion object {
        private const val BATCH_SIZE = 50
    }
}
