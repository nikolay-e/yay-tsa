package dev.yaytsa.adapterjellyfin

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
        return getExact(trackName, artistName, albumName, durationSeconds)
            ?: search(trackName, artistName)
    }

    private fun getExact(
        trackName: String,
        artistName: String,
        albumName: String?,
        durationSeconds: Long?,
    ): String? {
        val params =
            buildString {
                append("track_name=").append(enc(trackName))
                append("&artist_name=").append(enc(artistName))
                if (!albumName.isNullOrBlank()) append("&album_name=").append(enc(albumName))
                if (durationSeconds != null && durationSeconds > 0) append("&duration=").append(durationSeconds)
            }
        val node = get("/api/get?$params") ?: return null
        return pickLyrics(node)
    }

    private fun search(
        trackName: String,
        artistName: String,
    ): String? {
        val params = "track_name=${enc(trackName)}&artist_name=${enc(artistName)}"
        val node = get("/api/search?$params") ?: return null
        if (!node.isArray || node.isEmpty) return null
        return node.firstNotNullOfOrNull { pickLyrics(it) }
    }

    // Prefer synced (LRC with timestamps) so the PWA overlay can scroll; fall back to plain text.
    private fun pickLyrics(node: JsonNode): String? {
        val synced = node.path("syncedLyrics").asText("")
        if (synced.isNotBlank()) return synced
        val plain = node.path("plainLyrics").asText("")
        return plain.ifBlank { null }
    }

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
