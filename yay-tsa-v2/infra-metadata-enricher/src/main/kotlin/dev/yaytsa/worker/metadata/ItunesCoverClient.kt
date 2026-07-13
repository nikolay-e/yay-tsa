package dev.yaytsa.worker.metadata

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

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
    userAgent: String,
    rateLimiter: RateLimiter,
) {
    private val http = ProviderHttp("iTunes", userAgent, rateLimiter, ProviderHttp.TransientFailurePolicy.THROW_UNAVAILABLE)

    private val mapper =
        jacksonObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

    fun fetchAlbumCover(
        artistName: String?,
        albumName: String,
    ): CoverArt? {
        val term = listOfNotNull(artistName, albumName).joinToString(" ").trim().ifBlank { return null }
        val artworkUrl = resolveArtworkUrl(term) ?: return null
        return http.getImage(artworkUrl)
    }

    private fun resolveArtworkUrl(term: String): String? {
        val encoded = URLEncoder.encode(term, StandardCharsets.UTF_8)
        val body = http.getString("$baseUrl/search?term=$encoded&media=music&entity=album&limit=1") ?: return null
        val parsed = runCatching { mapper.readValue<ItunesSearchResult>(body) }.getOrNull() ?: return null
        val thumb =
            parsed.results
                .firstOrNull()
                ?.artworkUrl100
                ?.takeIf { it.isNotBlank() } ?: return null
        // 100x100bb.jpg -> 600x600bb.jpg; Apple's CDN serves any square size from the same path.
        return thumb.replace(Regex("/\\d+x\\d+bb"), "/600x600bb")
    }
}
