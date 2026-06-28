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
private data class DeezerSearchResult(
    val data: List<DeezerAlbum> = emptyList(),
)

@JsonIgnoreProperties(ignoreUnknown = true)
private data class DeezerAlbum(
    val cover_xl: String? = null,
    val cover_big: String? = null,
)

// Keyless cover fallback via the Deezer public API. Strong coverage for international / non-Western
// catalogues (incl. Russian) that MusicBrainz/Cover Art Archive frequently miss. Resolves artist+album
// to the largest available square cover URL, then downloads it. No-match -> null (chain falls through);
// transient 429/5xx -> throw so the entity is parked and retried rather than silently marked checked.
class DeezerCoverClient(
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
        val query = listOfNotNull(artistName, albumName).joinToString(" ").trim().ifBlank { return null }
        val coverUrl = resolveCoverUrl(query) ?: return null
        return downloadImage(coverUrl)
    }

    private fun resolveCoverUrl(query: String): String? {
        val encoded = URLEncoder.encode(query, StandardCharsets.UTF_8)
        val uri = URI.create("$baseUrl/search/album?q=$encoded&limit=1")
        val body = getString(uri) ?: return null
        val parsed = runCatching { mapper.readValue<DeezerSearchResult>(body) }.getOrNull() ?: return null
        val album = parsed.data.firstOrNull() ?: return null
        return listOfNotNull(album.cover_xl, album.cover_big).firstOrNull { it.isNotBlank() }
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
                .getOrElse { throw MetadataProviderUnavailableException("Deezer request to $uri failed: ${it.message}", it) }
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
                .getOrElse { throw MetadataProviderUnavailableException("Deezer artwork download $uri failed: ${it.message}", it) }
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
            429, in 500..599 -> throw MetadataProviderUnavailableException("Deezer transient $code for $uri")
            else -> {
                log.debug("Deezer returned {} for {}", code, uri)
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
