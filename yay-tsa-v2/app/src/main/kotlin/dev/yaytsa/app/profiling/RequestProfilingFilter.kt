package dev.yaytsa.app.profiling

import jakarta.persistence.EntityManagerFactory
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.hibernate.SessionFactory
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import org.springframework.web.util.ContentCachingResponseWrapper
import java.util.UUID

/**
 * Opt-in per-request profiler: logs a correlation id, method+URI, status, wall duration, the number
 * of SQL statements the request issued (Hibernate statistics delta), and the serialized response
 * size. Enable with `yaytsa.profiling.enabled=true` — off by default so production has no overhead.
 *
 * The Hibernate statement counter is global, so the per-request delta is only exact under low
 * concurrency. That is the intended use: a local/staging profiling run hitting one endpoint at a
 * time, matching the ItemsEndpointBenchmark methodology.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
@ConditionalOnProperty(name = ["yaytsa.profiling.enabled"], havingValue = "true")
class RequestProfilingFilter(
    entityManagerFactory: EntityManagerFactory,
) : OncePerRequestFilter() {
    private val log = LoggerFactory.getLogger("RequestProfiling")
    private val statistics =
        entityManagerFactory.unwrap(SessionFactory::class.java).statistics.also {
            it.isStatisticsEnabled = true
        }

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val correlationId = UUID.randomUUID().toString().take(8)
        response.setHeader("X-Correlation-Id", correlationId)
        val wrapped = ContentCachingResponseWrapper(response)
        val sqlBefore = statistics.prepareStatementCount
        val startNanos = System.nanoTime()
        try {
            filterChain.doFilter(request, wrapped)
        } finally {
            val durationMs = (System.nanoTime() - startNanos) / 1_000_000.0
            val sqlCount = statistics.prepareStatementCount - sqlBefore
            val uri = request.requestURI + (request.queryString?.let { "?$it" } ?: "")
            log.info(
                "cid={} {} {} status={} {}ms sql={} bytes={}",
                correlationId,
                request.method,
                uri,
                wrapped.status,
                "%.1f".format(durationMs),
                sqlCount,
                wrapped.contentSize,
            )
            wrapped.copyBodyToResponse()
        }
    }
}
