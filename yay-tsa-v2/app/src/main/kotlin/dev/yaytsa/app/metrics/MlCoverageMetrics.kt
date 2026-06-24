package dev.yaytsa.app.metrics

import dev.yaytsa.application.ml.port.EmbeddingCoverage
import dev.yaytsa.application.ml.port.MlQueryPort
import io.micrometer.core.instrument.MeterRegistry
import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.util.concurrent.atomic.AtomicReference

/**
 * Makes embedding coverage operator-visible. Radio quality is a direct function of how much of the
 * library the ML worker has actually analyzed, and that backfill is slow (one track at a time on a
 * time-sliced GPU). Without this gauge "radio degrades on partial coverage" is an invisible state;
 * with it there's a number to alert on (e.g. `yaytsa_ml_coverage{space="mert"} < 0.8`).
 *
 * The counts are sampled on a slow schedule into a holder so Prometheus scrapes never hit the DB.
 */
@Component
class MlCoverageMetrics(
    private val mlQuery: MlQueryPort,
    private val meterRegistry: MeterRegistry,
) {
    private val holder = AtomicReference(EmbeddingCoverage(0, 0, 0, 0, 0))

    @PostConstruct
    fun register() {
        meterRegistry.gauge("yaytsa.ml.tracks.total", holder) { it.get().total.toDouble() }
        meterRegistry.gauge(
            "yaytsa.ml.coverage",
            listOf(
                io.micrometer.core.instrument.Tag
                    .of("space", "mert"),
            ),
            holder,
        ) { fraction(it.get().mert, it.get().total) }
        meterRegistry.gauge(
            "yaytsa.ml.coverage",
            listOf(
                io.micrometer.core.instrument.Tag
                    .of("space", "clap"),
            ),
            holder,
        ) { fraction(it.get().clap, it.get().total) }
        meterRegistry.gauge(
            "yaytsa.ml.coverage",
            listOf(
                io.micrometer.core.instrument.Tag
                    .of("space", "discogs"),
            ),
            holder,
        ) {
            fraction(it.get().discogs, it.get().total)
        }
        meterRegistry.gauge(
            "yaytsa.ml.coverage",
            listOf(
                io.micrometer.core.instrument.Tag
                    .of("space", "musicnn"),
            ),
            holder,
        ) {
            fraction(it.get().musicnn, it.get().total)
        }
    }

    @Scheduled(fixedDelayString = "\${yaytsa.ml.coverage-refresh-ms:60000}", initialDelay = 20_000)
    fun refresh() {
        try {
            holder.set(mlQuery.embeddingCoverage())
        } catch (e: Exception) {
            logger.debug("ML coverage refresh failed (DB not ready?): {}", e.message)
        }
    }

    private fun fraction(
        part: Long,
        total: Long,
    ): Double = if (total > 0) part.toDouble() / total else 0.0

    private companion object {
        val logger = LoggerFactory.getLogger(MlCoverageMetrics::class.java)
    }
}
