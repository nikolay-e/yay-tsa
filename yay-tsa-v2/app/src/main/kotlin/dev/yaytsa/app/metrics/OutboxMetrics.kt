package dev.yaytsa.app.metrics

import dev.yaytsa.application.shared.port.Clock
import dev.yaytsa.persistence.shared.jpa.OutboxJpaRepository
import io.micrometer.core.instrument.MeterRegistry
import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Duration
import java.util.concurrent.atomic.AtomicReference

@Component
class OutboxMetrics(
    private val outboxJpa: OutboxJpaRepository,
    private val clock: Clock,
    private val meterRegistry: MeterRegistry,
) {
    private data class OutboxBacklog(
        val pending: Long,
        val oldestAgeSeconds: Double,
    )

    private val holder = AtomicReference(OutboxBacklog(0, 0.0))

    @PostConstruct
    fun register() {
        meterRegistry.gauge("yaytsa.outbox.pending", holder) { it.get().pending.toDouble() }
        meterRegistry.gauge("yaytsa.outbox.oldest.age.seconds", holder) { it.get().oldestAgeSeconds }
    }

    @Scheduled(fixedDelayString = "\${yaytsa.outbox.metrics-refresh-ms:15000}", initialDelay = 10_000)
    fun refresh() {
        try {
            val pending = outboxJpa.countByPublishedAtIsNull()
            val oldest = outboxJpa.findFirstByPublishedAtIsNullOrderByCreatedAtAsc()
            val oldestAgeSeconds =
                oldest
                    ?.let { Duration.between(it.createdAt, clock.now()).toMillis() / 1000.0 }
                    ?.coerceAtLeast(0.0) ?: 0.0
            holder.set(OutboxBacklog(pending, oldestAgeSeconds))
        } catch (e: Exception) {
            logger.debug("Outbox metrics refresh failed (DB not ready?): {}", e.message)
        }
    }

    private companion object {
        val logger = LoggerFactory.getLogger(OutboxMetrics::class.java)
    }
}
