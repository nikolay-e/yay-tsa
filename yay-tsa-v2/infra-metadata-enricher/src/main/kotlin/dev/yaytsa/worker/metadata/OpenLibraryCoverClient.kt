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
data class OpenLibrarySearchResult(
    val docs: List<OpenLibraryDoc> = emptyList(),
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class OpenLibraryDoc(
    val cover_i: Long? = null,
)

// Keyless open-source book-cover fallback for audiobooks. Resolves title+author via search.json to a
// cover id (the CoverID path is NOT rate-limited, unlike the ISBN path), then fetches the L-size image
// with ?default=false so a missing cover returns a real 404 instead of a cached blank placeholder.
// Fault-tolerant by design: any HTTP/parse error returns null so the enrichment loop falls through.
class OpenLibraryCoverClient(
    private val searchBaseUrl: String,
    private val coversBaseUrl: String,
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

    fun fetchCover(
        title: String,
        author: String?,
    ): CoverArt? {
        val normalizedTitle = normalizeTitle(title).ifBlank { return null }
        val coverId = resolveCoverId(normalizedTitle, author) ?: return null
        return fetchCoverById(coverId)
    }

    private fun resolveCoverId(
        title: String,
        author: String?,
    ): Long? {
        val query =
            buildString {
                append("title=").append(encode(title))
                if (!author.isNullOrBlank()) append("&author=").append(encode(author))
                append("&fields=cover_i&limit=1")
            }
        val body =
            runCatching { get("$searchBaseUrl/search.json?$query") }
                .getOrElse {
                    log.warn("Open Library search failed for '{}': {}", title, it.message)
                    return null
                } ?: return null
        val parsed =
            runCatching { mapper.readValue<OpenLibrarySearchResult>(body) }
                .getOrElse {
                    log.warn("Open Library search parse failed for '{}': {}", title, it.message)
                    return null
                }
        return parsed.docs.firstNotNullOfOrNull { it.cover_i }?.takeIf { it > 0 }
    }

    private fun fetchCoverById(coverId: Long): CoverArt? {
        val uri = URI.create("$coversBaseUrl/b/id/$coverId-L.jpg?default=false")
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
                    log.warn("Open Library cover fetch failed for id {}: {}", coverId, it.message)
                    return null
                }
        return when (val code = response.statusCode()) {
            in 200..299 -> {
                val bytes = response.body()
                if (bytes.isEmpty()) null else CoverArt(bytes, "jpg")
            }
            else -> {
                log.debug("Open Library returned {} for cover id {}", code, coverId)
                null
            }
        }
    }

    private fun get(url: String): String? {
        val uri = URI.create(url)
        rateLimiter.acquire()
        val request =
            HttpRequest
                .newBuilder(uri)
                .header("User-Agent", userAgent)
                .header("Accept", "application/json")
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build()
        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        return when (response.statusCode()) {
            in 200..299 -> response.body()
            else -> null
        }
    }

    private fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)

    companion object {
        private val noisePatterns =
            listOf(
                Regex("\\([^)]*\\)"),
                Regex("\\[[^]]*]"),
                Regex("(?i)\\b(un)?abridged\\b"),
                Regex("(?i):\\s*a\\s+(novel|memoir|story|thriller|mystery)\\b.*$"),
                Regex("(?i),?\\s*book\\s+\\d+.*$"),
                Regex("(?i),?\\s*vol(\\.|ume)?\\s*\\d+.*$"),
            )

        fun normalizeTitle(raw: String): String {
            var result = raw
            for (pattern in noisePatterns) {
                result = pattern.replace(result, " ")
            }
            return result
                .replace(Regex("\\s+"), " ")
                .trim()
                .trim('-', ':', ',')
                .trim()
        }
    }
}
