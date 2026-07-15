package dev.yaytsa.app.security

import com.fasterxml.jackson.databind.ObjectMapper
import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import io.github.bucket4j.Bandwidth
import io.github.bucket4j.Bucket
import io.micrometer.core.instrument.MeterRegistry
import jakarta.servlet.FilterChain
import jakarta.servlet.ReadListener
import jakarta.servlet.ServletInputStream
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletRequestWrapper
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.authentication.AnonymousAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.filter.OncePerRequestFilter
import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.InputStreamReader
import java.time.Duration

class AuthRateLimitFilter(
    private val meterRegistry: MeterRegistry,
    private val objectMapper: ObjectMapper,
    private val maxFailuresPerMinute: Long,
) : OncePerRequestFilter() {
    private val buckets: Cache<String, Bucket> =
        Caffeine
            .newBuilder()
            .maximumSize(MAX_TRACKED_KEYS)
            .expireAfterAccess(Duration.ofMinutes(10))
            .build()

    override fun shouldNotFilter(request: HttpServletRequest): Boolean =
        !isJellyfinLogin(request) && !isSubsonicRequest(request) && !isOAuthPasswordCheck(request)

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val protocol =
            when {
                isJellyfinLogin(request) -> "jellyfin"
                isOAuthPasswordCheck(request) -> "oauth"
                else -> "subsonic"
            }
        val (effectiveRequest, username) =
            when (protocol) {
                "jellyfin" -> replayableRequestWithUsername(request)
                "oauth" -> request to request.getParameter("username")
                else -> request to request.getParameter("u")
            }

        val scopedKeys = failureBucketKeys(request, username)
        val exhaustedScope = scopedKeys.entries.firstOrNull { bucketFor(it.value).availableTokens <= 0 }
        if (exhaustedScope != null) {
            reject(response, bucketFor(exhaustedScope.value), protocol, exhaustedScope.key)
            return
        }

        filterChain.doFilter(effectiveRequest, response)

        if (authFailed(protocol, response)) {
            meterRegistry.counter("yaytsa.auth.failures", "protocol", protocol).increment()
            scopedKeys.values.forEach { bucketFor(it).tryConsume(1) }
        }
    }

    private fun isJellyfinLogin(request: HttpServletRequest): Boolean = request.method == "POST" && request.requestURI == "/Users/AuthenticateByName"

    private fun isSubsonicRequest(request: HttpServletRequest): Boolean = request.requestURI.startsWith("/rest/")

    private fun isOAuthPasswordCheck(request: HttpServletRequest): Boolean = request.method == "POST" && request.requestURI == "/oauth/authorize"

    private fun failureBucketKeys(
        request: HttpServletRequest,
        username: String?,
    ): Map<String, String> =
        buildMap {
            put("ip", "ip:${request.remoteAddr}")
            username?.takeIf { it.isNotBlank() }?.let { put("username", "user:${it.lowercase()}") }
        }

    private fun authFailed(
        protocol: String,
        response: HttpServletResponse,
    ): Boolean =
        if (protocol == "subsonic") {
            val authentication = SecurityContextHolder.getContext().authentication
            authentication == null || authentication is AnonymousAuthenticationToken || !authentication.isAuthenticated
        } else {
            response.status == HttpServletResponse.SC_UNAUTHORIZED
        }

    private fun bucketFor(key: String): Bucket =
        buckets.get(key) {
            Bucket
                .builder()
                .addLimit(
                    Bandwidth
                        .builder()
                        .capacity(maxFailuresPerMinute)
                        .refillGreedy(maxFailuresPerMinute, Duration.ofMinutes(1))
                        .build(),
                ).build()
        }

    private fun reject(
        response: HttpServletResponse,
        bucket: Bucket,
        protocol: String,
        scope: String,
    ) {
        meterRegistry.counter("yaytsa.auth.throttled", "protocol", protocol, "scope", scope).increment()
        val nanosToWait = bucket.estimateAbilityToConsume(1).nanosToWaitForRefill
        val retryAfterSeconds = ((nanosToWait + NANOS_PER_SECOND - 1) / NANOS_PER_SECOND).coerceAtLeast(1)
        response.status = 429
        response.setHeader("Retry-After", retryAfterSeconds.toString())
        response.contentType = "application/problem+json"
        response.writer.write(
            """{"title":"Too Many Requests","status":429,"detail":"Too many failed authentication attempts. Try again later."}""",
        )
    }

    private fun replayableRequestWithUsername(request: HttpServletRequest): Pair<HttpServletRequest, String?> {
        val contentLength = request.contentLengthLong
        if (contentLength < 0 || contentLength > MAX_CACHED_BODY_BYTES) return request to null
        val body = request.inputStream.readAllBytes()
        val username =
            runCatching {
                val root = objectMapper.readTree(body)
                root.path("Username").asText(null) ?: root.path("username").asText(null)
            }.getOrNull()
        return CachedBodyRequest(request, body) to username
    }

    private class CachedBodyRequest(
        request: HttpServletRequest,
        private val body: ByteArray,
    ) : HttpServletRequestWrapper(request) {
        override fun getInputStream(): ServletInputStream {
            val delegate = ByteArrayInputStream(body)
            return object : ServletInputStream() {
                override fun read(): Int = delegate.read()

                override fun read(
                    b: ByteArray,
                    off: Int,
                    len: Int,
                ): Int = delegate.read(b, off, len)

                override fun isFinished(): Boolean = delegate.available() == 0

                override fun isReady(): Boolean = true

                override fun setReadListener(listener: ReadListener) = throw UnsupportedOperationException()
            }
        }

        override fun getReader(): BufferedReader = BufferedReader(InputStreamReader(inputStream, characterEncoding ?: Charsets.UTF_8.name()))
    }

    private companion object {
        const val MAX_TRACKED_KEYS = 100_000L
        const val MAX_CACHED_BODY_BYTES = 16_384L
        const val NANOS_PER_SECOND = 1_000_000_000L
    }
}
