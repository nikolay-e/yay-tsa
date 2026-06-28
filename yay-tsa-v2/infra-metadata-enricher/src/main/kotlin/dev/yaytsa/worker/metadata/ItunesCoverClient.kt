package dev.yaytsa.worker.metadata

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.slf4j.LoggerFactory
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration

@JsonIgnoreProperties(ignoreUnknown = true)
private data class ItunesSearchResult(
    val results: List<ItunesAlbum> = emptyList(),
)

@JsonIgnoreProperties(ignoreUnknown = true)
private data class ItunesAlbum(
    val artworkUrl100: String? = null,
)

// Keyless cover fallback via the iTunes Search API. Resolves artist+album to the album artwork URL,
// then upscales the 100px thumbnail URL to a full-size image by rewriting the size segment (Apple's
// CDN honours arbitrary square sizes). A no-match returns null so the enrichment chain falls through;
// a transient 429/5xx throws so the entity is parked and retried, never silently marked checked.
class ItunesCoverClient(
    private val baseUrl: String,
    private val userAgent: String,
    private val rateLimiter: RateLimiter,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    private val httpClient: HttpClient =
        HttpClient
            .newBuilder()
            .followRedirects(HttpClient.Redirect.ALWAYS)
            .connectTimeout(Duration.ofSeconds(10))
            .build()

    private val mapper =
        jacksonObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

    fun fetchAlbumCover(
        artistName: String?,
        albumName: String,
    ): CoverArt? {
        val term = listOfNotNull(artistName, albumName).joinToString(" ").trim().ifBlank { return null }
        val artworkUrl = resolveArtworkUrl(term) ?: return null
        return downloadImage(artworkUrl)
    }

    private fun resolveArtworkUrl(term: String): String? {
        val encoded = URLEncoder.encode(term, StandardCharsets.UTF_8)
        val uri = URI.create("$baseUrl/search?term=$encoded&media=music&entity=album&limit=1")
        val body = getString(uri) ?: return null
        val parsed = runCatching { mapper.readValue<ItunesSearchResult>(body) }.getOrNull() ?: return null
        val thumb =
            parsed.results
                .firstOrNull()
                ?.artworkUrl100
                ?.takeIf { it.isNotBlank() } ?: return null
        // 100x100bb.jpg -> 600x600bb.jpg; Apple's CDN serves any square size from the same path.
        return thumb.replace(Regex("/\\d+x\\d+bb"), "/600x600bb")
    }

    private fun getString(uri: URI): String? {
        rateLimiter.acquire()
        val request =
            HttpRequest
                .newBuilder(uri)
                .header("User-Agent", userAgent)
                .timeout(Duration.ofSeconds(15))
                .GET()
                .build()
        val response =
            runCatching { httpClient.send(request, HttpResponse.BodyHandlers.ofString()) }
                .getOrElse { throw MetadataProviderUnavailableException("iTunes request to $uri failed: ${it.message}", it) }
        return handleStatus(response.statusCode(), uri) { response.body() }
    }

    private fun downloadImage(url: String): CoverArt? {
        val uri = runCatching { URI.create(url) }.getOrNull() ?: return null
        rateLimiter.acquire()
        val request =
            HttpRequest
                .newBuilder(uri)
                .header("User-Agent", userAgent)
                .timeout(Duration.ofSeconds(20))
                .GET()
                .build()
        val response =
            runCatching { httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray()) }
                .getOrElse { throw MetadataProviderUnavailableException("iTunes artwork download $uri failed: ${it.message}", it) }
        return handleStatus(response.statusCode(), uri) {
            val bytes = response.body()
            if (bytes.isEmpty()) return@handleStatus null
            val contentType = response.headers().firstValue("Content-Type").orElse("")
            CoverArt(bytes, extensionFor(contentType))
        }
    }

    private fun <T> handleStatus(
        code: Int,
        uri: URI,
        onSuccess: () -> T?,
    ): T? =
        when (code) {
            in 200..299 -> onSuccess()
            404 -> null
            429, in 500..599 -> throw MetadataProviderUnavailableException("iTunes transient $code for $uri")
            else -> {
                log.debug("iTunes returned {} for {}", code, uri)
                null
            }
        }

    private fun extensionFor(contentType: String): String =
        when {
            contentType.contains("png", ignoreCase = true) -> "png"
            contentType.contains("webp", ignoreCase = true) -> "webp"
            else -> "jpg"
        }
}
