package dev.yaytsa.worker.metadata

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

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
    userAgent: String,
    rateLimiter: RateLimiter,
) {
    private val http = ProviderHttp("Deezer", userAgent, rateLimiter, ProviderHttp.TransientFailurePolicy.THROW_UNAVAILABLE)

    private val mapper =
        jacksonObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

    fun fetchAlbumCover(
        artistName: String?,
        albumName: String,
    ): CoverArt? {
        val query = listOfNotNull(artistName, albumName).joinToString(" ").trim().ifBlank { return null }
        val coverUrl = resolveCoverUrl(query) ?: return null
        return http.getImage(coverUrl)
    }

    private fun resolveCoverUrl(query: String): String? {
        val encoded = URLEncoder.encode(query, StandardCharsets.UTF_8)
        val body = http.getString("$baseUrl/search/album?q=$encoded&limit=1") ?: return null
        val parsed = runCatching { mapper.readValue<DeezerSearchResult>(body) }.getOrNull() ?: return null
        val album = parsed.data.firstOrNull() ?: return null
        return listOfNotNull(album.cover_xl, album.cover_big).firstOrNull { it.isNotBlank() }
    }
}
