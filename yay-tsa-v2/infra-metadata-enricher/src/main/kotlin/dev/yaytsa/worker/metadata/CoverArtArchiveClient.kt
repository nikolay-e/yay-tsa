package dev.yaytsa.worker.metadata

import org.slf4j.LoggerFactory
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

data class CoverArt(
    val bytes: ByteArray,
    val extension: String,
)

class CoverArtArchiveClient(
    private val baseUrl: String,
    private val userAgent: String,
    private val rateLimiter: RateLimiter,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    private val httpClient: HttpClient =
        HttpClient
            .newBuilder()
            .followRedirects(HttpClient.Redirect.ALWAYS)
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(10))
            .build()

    fun fetchReleaseGroupFront(releaseGroupMbid: String): CoverArt? {
        val sized = fetch("/release-group/$releaseGroupMbid/front-500")
        if (sized != null) return sized
        return fetch("/release-group/$releaseGroupMbid/front")
    }

    private fun fetch(path: String): CoverArt? {
        val uri = URI.create(baseUrl + path)
        rateLimiter.acquire()
        val request =
            HttpRequest
                .newBuilder(uri)
                .header("User-Agent", userAgent)
                .timeout(Duration.ofSeconds(15))
                .GET()
                .build()
        val response =
            runCatching { httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray()) }
                .getOrElse {
                    throw MetadataProviderUnavailableException(
                        "Cover Art Archive request to $uri failed: ${it.message}",
                        it,
                    )
                }
        return when (val code = response.statusCode()) {
            in 200..299 -> {
                val contentType = response.headers().firstValue("Content-Type").orElse("")
                CoverArt(response.body(), extensionFor(contentType))
            }
            404 -> null
            429, 503, in 500..599 ->
                throw MetadataProviderUnavailableException("Cover Art Archive transient $code for $uri")
            else -> {
                log.debug("Cover Art Archive returned {} for {}", code, uri)
                null
            }
        }
    }

    private fun extensionFor(contentType: String): String =
        when {
            contentType.contains("png", ignoreCase = true) -> "png"
            contentType.contains("gif", ignoreCase = true) -> "gif"
            contentType.contains("webp", ignoreCase = true) -> "webp"
            else -> "jpg"
        }
}
