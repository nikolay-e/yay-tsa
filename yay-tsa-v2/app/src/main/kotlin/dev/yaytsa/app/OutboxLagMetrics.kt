package dev.yaytsa.app

import dev.yaytsa.application.shared.port.Clock
import dev.yaytsa.persistence.shared.jpa.OutboxJpaRepository
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.context.annotation.Configuration
import java.time.Duration

// The outbox poller is the delivery path for every SSE notification and remote command.
// It fails SILENTLY: a starved scheduler or a wedged publisher leaves rows unpublished
// while all HTTP surfaces stay green (2026-07-10: a long karaoke batch on the shared
// scheduler thread froze command delivery for hours). Oldest-unpublished-age is the one
// signal that catches the whole class; the alert rule lives in charts/yay-tsa-v2.
@Configuration
class OutboxLagMetrics(
    registry: MeterRegistry,
    outboxRepo: OutboxJpaRepository,
    clock: Clock,
) {
    init {
        Gauge
            .builder("yaytsa.outbox.oldest.unpublished.age.seconds") {
                val oldest = outboxRepo.findFirstByPublishedAtIsNullOrderByCreatedAtAsc() ?: return@builder 0.0
                Duration
                    .between(oldest.createdAt, clock.now())
                    .seconds
                    .coerceAtLeast(0)
                    .toDouble()
            }.description("Age of the oldest unpublished outbox row; sustained growth means notification delivery is stalled")
            .register(registry)
        Gauge
            .builder("yaytsa.outbox.unpublished.count") {
                outboxRepo.countByPublishedAtIsNull().toDouble()
            }.description("Unpublished outbox rows")
            .register(registry)
    }
}
