package dev.yaytsa.adaptershared

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration

@Component
class LrclibClient(
    @Value("\${yaytsa.lyrics.lrclib.base-url:https://lrclib.net}") private val baseUrl: String,
    private val objectMapper: ObjectMapper,
) {
    private val httpClient =
        HttpClient
            .newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build()

    fun fetch(
        trackName: String,
        artistName: String?,
        albumName: String?,
        durationSeconds: Long?,
    ): String? {
        if (trackName.isBlank() || artistName.isNullOrBlank()) return null

        val candidates = mutableListOf<JsonNode>()
        getExact(trackName, artistName, albumName, durationSeconds)?.let { candidates.add(it) }

        candidates.firstNotNullOfOrNull(::syncedOf)?.let { return it }

        candidates.addAll(search(trackName, artistName))
        return candidates.firstNotNullOfOrNull(::syncedOf)
            ?: candidates.firstNotNullOfOrNull(::plainOf)
    }

    private fun getExact(
        trackName: String,
        artistName: String,
        albumName: String?,
        durationSeconds: Long?,
    ): JsonNode? {
        val params =
            buildString {
                append("track_name=").append(enc(trackName))
                append("&artist_name=").append(enc(artistName))
                if (!albumName.isNullOrBlank()) append("&album_name=").append(enc(albumName))
                if (durationSeconds != null && durationSeconds > 0) append("&duration=").append(durationSeconds)
            }
        return get("/api/get?$params")
    }

    private fun search(
        trackName: String,
        artistName: String,
    ): List<JsonNode> {
        val params = "track_name=${enc(trackName)}&artist_name=${enc(artistName)}"
        val node = get("/api/search?$params") ?: return emptyList()
        if (!node.isArray) return emptyList()
        return node.toList()
    }

    private fun syncedOf(node: JsonNode): String? = node.path("syncedLyrics").asText("").ifBlank { null }

    private fun plainOf(node: JsonNode): String? = node.path("plainLyrics").asText("").ifBlank { null }

    private fun get(path: String): JsonNode? =
        try {
            val request =
                HttpRequest
                    .newBuilder()
                    .uri(URI.create(baseUrl.trimEnd('/') + path))
                    .timeout(Duration.ofSeconds(8))
                    .header("User-Agent", USER_AGENT)
                    .GET()
                    .build()
            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() == 200) objectMapper.readTree(response.body()) else null
        } catch (e: Exception) {
            log.warn("LRCLIB request failed for {}: {}", path, e.message)
            null
        }

    private fun enc(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)

    companion object {
        private val log = LoggerFactory.getLogger(LrclibClient::class.java)
        private const val USER_AGENT = "yay-tsa (https://github.com/nikolay-e/yay-tsa)"
    }
}
