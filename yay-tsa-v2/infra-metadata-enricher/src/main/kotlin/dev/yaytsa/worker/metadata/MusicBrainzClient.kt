package dev.yaytsa.worker.metadata

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
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
import kotlin.random.Random

@JsonIgnoreProperties(ignoreUnknown = true)
data class MbReleaseGroupSearch(
    @JsonProperty("release-groups") val releaseGroups: List<MbEntity> = emptyList(),
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class MbArtistSearch(
    @JsonProperty("artists") val artists: List<MbEntity> = emptyList(),
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class MbReleaseSearch(
    @JsonProperty("releases") val releases: List<MbRelease> = emptyList(),
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class MbEntity(
    val id: String = "",
    val score: Int = 0,
    val title: String? = null,
    val name: String? = null,
    @JsonProperty("first-release-date") val firstReleaseDate: String? = null,
    @JsonProperty("artist-credit") val artistCredit: List<MbArtistCredit> = emptyList(),
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class MbRelease(
    val id: String = "",
    val score: Int = 0,
    val title: String? = null,
    @JsonProperty("track-count") val trackCount: Int? = null,
    @JsonProperty("release-group") val releaseGroup: MbReleaseGroupRef? = null,
    @JsonProperty("artist-credit") val artistCredit: List<MbArtistCredit> = emptyList(),
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class MbReleaseGroupRef(
    val id: String = "",
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class MbArtistCredit(
    val name: String? = null,
)

class MusicBrainzClient(
    private val baseUrl: String,
    private val userAgent: String,
    private val rateLimiter: RateLimiter,
    private val maxRetries: Int = 3,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    private val httpClient: HttpClient =
        HttpClient
            .newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(10))
            .build()

    private val mapper =
        jacksonObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

    fun searchReleaseGroups(
        album: String,
        artist: String?,
    ): List<MetadataCandidate> {
        val query =
            buildString {
                append("releasegroup:\"").append(escapeLucene(album)).append("\"")
                if (!artist.isNullOrBlank() && !ReleaseMatcher.isVariousArtists(artist)) {
                    append(" AND artist:\"").append(escapeLucene(artist)).append("\"")
                }
            }
        val body = get("/release-group?query=${encode(query)}&fmt=json&limit=10") ?: return emptyList()
        val parsed = runCatching { mapper.readValue<MbReleaseGroupSearch>(body) }.getOrNull() ?: return emptyList()
        return parsed.releaseGroups.map {
            MetadataCandidate(
                mbid = it.id,
                title = it.title.orEmpty(),
                artistName = it.artistCredit.firstOrNull()?.name,
                score = it.score,
                year = parseYear(it.firstReleaseDate),
            )
        }
    }

    fun searchArtists(name: String): List<MetadataCandidate> {
        val query = "artist:\"${escapeLucene(name)}\""
        val body = get("/artist?query=${encode(query)}&fmt=json&limit=10") ?: return emptyList()
        val parsed = runCatching { mapper.readValue<MbArtistSearch>(body) }.getOrNull() ?: return emptyList()
        return parsed.artists.map {
            MetadataCandidate(
                mbid = it.id,
                title = it.name.orEmpty(),
                artistName = it.name,
                score = it.score,
            )
        }
    }

    fun searchReleases(
        album: String,
        artist: String?,
    ): List<MbRelease> {
        val query =
            buildString {
                append("release:\"").append(escapeLucene(album)).append("\"")
                if (!artist.isNullOrBlank() && !ReleaseMatcher.isVariousArtists(artist)) {
                    append(" AND artist:\"").append(escapeLucene(artist)).append("\"")
                }
            }
        val body = get("/release?query=${encode(query)}&fmt=json&limit=10") ?: return emptyList()
        val parsed = runCatching { mapper.readValue<MbReleaseSearch>(body) }.getOrNull() ?: return emptyList()
        return parsed.releases
    }

    private fun get(path: String): String? {
        val uri = URI.create(baseUrl + path)
        var attempt = 0
        while (attempt <= maxRetries) {
            rateLimiter.acquire()
            val request =
                HttpRequest
                    .newBuilder(uri)
                    .header("User-Agent", userAgent)
                    .header("Accept", "application/json")
                    .timeout(Duration.ofSeconds(10))
                    .GET()
                    .build()
            val response =
                runCatching { httpClient.send(request, HttpResponse.BodyHandlers.ofString()) }
                    .getOrElse {
                        log.warn("MusicBrainz request to {} failed: {}", uri, it.message)
                        attempt++
                        backoff(attempt)
                        return@getOrElse null
                    }
            if (response == null) continue

            when (response.statusCode()) {
                in 200..299 -> return response.body()
                429, 503 -> {
                    attempt++
                    log.debug("MusicBrainz throttled ({}) for {}, retry {}", response.statusCode(), uri, attempt)
                    backoff(attempt)
                }
                404 -> return null
                else -> {
                    log.warn("MusicBrainz returned {} for {}", response.statusCode(), uri)
                    return null
                }
            }
        }
        return null
    }

    private fun backoff(attempt: Int) {
        val base = 500L * (1L shl (attempt - 1).coerceAtMost(5))
        val jitter = Random.nextLong(base / 2 + 1)
        try {
            Thread.sleep(base + jitter)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw e
        }
    }

    private fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)

    private fun escapeLucene(value: String): String = value.replace("\\", "\\\\").replace("\"", "\\\"")

    private fun parseYear(date: String?): Int? = date?.take(4)?.toIntOrNull()
}
