package dev.yaytsa.app.security

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

@Component
class RateLimitFilter : OncePerRequestFilter() {
    private val attempts = ConcurrentHashMap<String, WindowCounter>()
    private val maxAttempts = 10
    private val windowMs = 60_000L // 1 minute

    override fun shouldNotFilter(request: HttpServletRequest): Boolean {
        val path = request.requestURI
        return path != "/Users/AuthenticateByName" &&
            !path.startsWith("/rest/")
    }

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val ip = request.remoteAddr ?: "unknown"
        val key = "$ip:${request.requestURI}"
        val counter = attempts.computeIfAbsent(key) { WindowCounter() }

        if (counter.incrementAndCheck(maxAttempts, windowMs)) {
            filterChain.doFilter(request, response)
        } else {
            response.status = 429
            response.contentType = "application/json"
            response.writer.write("""{"error":"Too many requests. Try again later."}""")
        }
    }

    private class WindowCounter {
        @Volatile var windowStart = System.currentTimeMillis()
        val count = AtomicInteger(0)

        fun incrementAndCheck(
            max: Int,
            windowMs: Long,
        ): Boolean {
            val now = System.currentTimeMillis()
            if (now - windowStart > windowMs) {
                synchronized(this) {
                    if (now - windowStart > windowMs) {
                        windowStart = now
                        count.set(0)
                    }
                }
            }
            return count.incrementAndGet() <= max
        }
    }
}
