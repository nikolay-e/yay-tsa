package dev.yaytsa.worker.metadata

import org.slf4j.LoggerFactory
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

// Shared rate-limited GET plumbing for the cover/image providers. Two transient-failure policies:
// THROW_UNAVAILABLE (CAA/iTunes/Deezer) maps connect failures and 429/5xx to
// MetadataProviderUnavailableException so the entity is parked and retried, never silently marked
// checked; RETURN_NULL (Open Library, artist images) treats any non-2xx as a clean miss and lets
// connect failures propagate to the caller's own fallback handling.
class ProviderHttp(
    private val providerName: String,
    private val userAgent: String,
    private val rateLimiter: RateLimiter,
    private val transientFailures: TransientFailurePolicy,
    httpVersion: HttpClient.Version? = null,
) {
    enum class TransientFailurePolicy { THROW_UNAVAILABLE, RETURN_NULL }

    private val log = LoggerFactory.getLogger(javaClass)

    private val httpClient: HttpClient =
        HttpClient
            .newBuilder()
            .followRedirects(HttpClient.Redirect.ALWAYS)
            .also { builder -> httpVersion?.let { builder.version(it) } }
            .connectTimeout(Duration.ofSeconds(10))
            .build()

    fun getString(
        url: String,
        timeout: Duration = Duration.ofSeconds(15),
        accept: String? = null,
    ): String? {
        val uri = URI.create(url)
        val response = send(uri, HttpResponse.BodyHandlers.ofString(), timeout, accept)
        return handleStatus(response.statusCode(), uri) { response.body() }
    }

    fun getImage(
        url: String,
        timeout: Duration = Duration.ofSeconds(20),
    ): CoverArt? {
        val uri = runCatching { URI.create(url) }.getOrNull() ?: return null
        val response = send(uri, HttpResponse.BodyHandlers.ofByteArray(), timeout, accept = null)
        return handleStatus(response.statusCode(), uri) {
            val bytes = response.body()
            if (bytes.isEmpty()) return@handleStatus null
            val contentType = response.headers().firstValue("Content-Type").orElse("")
            CoverArt(bytes, imageExtension(contentType, uri))
        }
    }

    private fun <B> send(
        uri: URI,
        bodyHandler: HttpResponse.BodyHandler<B>,
        timeout: Duration,
        accept: String?,
    ): HttpResponse<B> {
        rateLimiter.acquire()
        val request =
            HttpRequest
                .newBuilder(uri)
                .header("User-Agent", userAgent)
                .also { builder -> accept?.let { builder.header("Accept", it) } }
                .timeout(timeout)
                .GET()
                .build()
        return when (transientFailures) {
            TransientFailurePolicy.THROW_UNAVAILABLE ->
                runCatching { httpClient.send(request, bodyHandler) }
                    .getOrElse {
                        throw MetadataProviderUnavailableException("$providerName request to $uri failed: ${it.message}", it)
                    }
            TransientFailurePolicy.RETURN_NULL -> httpClient.send(request, bodyHandler)
        }
    }

    private fun <T> handleStatus(
        code: Int,
        uri: URI,
        onSuccess: () -> T?,
    ): T? =
        when {
            code in 200..299 -> onSuccess()
            transientFailures == TransientFailurePolicy.RETURN_NULL -> {
                log.debug("{} returned {} for {}", providerName, code, uri)
                null
            }
            code == 404 -> null
            code == 429 || code in 500..599 ->
                throw MetadataProviderUnavailableException("$providerName transient $code for $uri")
            else -> {
                log.debug("{} returned {} for {}", providerName, code, uri)
                null
            }
        }

    private fun imageExtension(
        contentType: String,
        uri: URI,
    ): String =
        when {
            contentType.contains("png", ignoreCase = true) -> "png"
            contentType.contains("gif", ignoreCase = true) -> "gif"
            contentType.contains("webp", ignoreCase = true) -> "webp"
            contentType.contains("jpeg", ignoreCase = true) || contentType.contains("jpg", ignoreCase = true) -> "jpg"
            else ->
                uri.path
                    .substringAfterLast('.', "jpg")
                    .lowercase()
                    .takeIf { it in setOf("png", "gif", "webp", "jpg", "jpeg") }
                    ?.let { if (it == "jpeg") "jpg" else it } ?: "jpg"
        }
}
