package dev.yaytsa.worker.metadata

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.slf4j.LoggerFactory
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration

data class FetchedImage(
    val bytes: ByteArray,
    val extension: String,
)

class ArtistImageSourceClient(
    private val userAgent: String,
    private val rateLimiter: RateLimiter,
    private val musicBrainzBaseUrl: String,
    private val wikidataBaseUrl: String = "https://www.wikidata.org/wiki",
    private val commonsApiUrl: String = "https://commons.wikimedia.org/w/api.php",
) {
    private val log = LoggerFactory.getLogger(javaClass)

    private val httpClient: HttpClient =
        HttpClient
            .newBuilder()
            .followRedirects(HttpClient.Redirect.ALWAYS)
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(10))
            .build()

    private val mapper =
        jacksonObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

    fun fetchArtistImage(musicbrainzId: String): FetchedImage? =
        runCatching {
            val wikidataQid = resolveWikidataQid(musicbrainzId) ?: return null
            val commonsFile = resolveCommonsFilename(wikidataQid) ?: return null
            val imageUrl = resolveCommonsImageUrl(commonsFile) ?: return null
            downloadImage(imageUrl)
        }.getOrElse {
            log.warn("Artist image lookup for mbid {} failed, falling back: {}", musicbrainzId, it.message)
            null
        }

    private fun resolveWikidataQid(musicbrainzId: String): String? {
        val body = getString("$musicBrainzBaseUrl/artist/$musicbrainzId?inc=url-rels&fmt=json") ?: return null
        val root = mapper.readTree(body)
        val relations = root.path("relations")
        if (!relations.isArray) return null
        for (relation in relations) {
            if (relation.path("type").asText() != "wikidata") continue
            val resource = relation.path("url").path("resource").asText("")
            val qid = resource.substringAfterLast('/').takeIf { it.startsWith("Q") }
            if (!qid.isNullOrBlank()) return qid
        }
        return null
    }

    private fun resolveCommonsFilename(wikidataQid: String): String? {
        val body = getString("$wikidataBaseUrl/Special:EntityData/$wikidataQid.json") ?: return null
        val root = mapper.readTree(body)
        val entity = root.path("entities").path(wikidataQid)
        val p18 = entity.path("claims").path("P18")
        if (!p18.isArray || p18.isEmpty) return null
        val filename =
            p18
                .first()
                .path("mainsnak")
                .path("datavalue")
                .path("value")
                .asText("")
        return filename.takeIf { it.isNotBlank() }
    }

    private fun resolveCommonsImageUrl(commonsFilename: String): String? {
        val title = URLEncoder.encode("File:$commonsFilename", StandardCharsets.UTF_8)
        val body =
            getString("$commonsApiUrl?action=query&prop=imageinfo&iiprop=url&titles=$title&format=json")
                ?: return null
        val pages = mapper.readTree(body).path("query").path("pages")
        if (!pages.isObject) return null
        for (page: JsonNode in pages) {
            val imageInfo = page.path("imageinfo")
            if (imageInfo.isArray && !imageInfo.isEmpty) {
                val url = imageInfo.first().path("url").asText("")
                if (url.isNotBlank()) return url
            }
        }
        return null
    }

    private fun downloadImage(url: String): FetchedImage? {
        rateLimiter.acquire()
        val request =
            HttpRequest
                .newBuilder(URI.create(url))
                .header("User-Agent", userAgent)
                .timeout(Duration.ofSeconds(20))
                .GET()
                .build()
        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray())
        if (response.statusCode() !in 200..299) {
            log.debug("Commons image download returned {} for {}", response.statusCode(), url)
            return null
        }
        val contentType = response.headers().firstValue("Content-Type").orElse("")
        return FetchedImage(response.body(), extensionFor(contentType, url))
    }

    private fun getString(rawUrl: String): String? {
        rateLimiter.acquire()
        val request =
            HttpRequest
                .newBuilder(URI.create(rawUrl))
                .header("User-Agent", userAgent)
                .header("Accept", "application/json")
                .timeout(Duration.ofSeconds(15))
                .GET()
                .build()
        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        return when (val code = response.statusCode()) {
            in 200..299 -> response.body()
            else -> {
                log.debug("Artist image source returned {} for {}", code, rawUrl)
                null
            }
        }
    }

    private fun extensionFor(
        contentType: String,
        url: String,
    ): String =
        when {
            contentType.contains("png", ignoreCase = true) -> "png"
            contentType.contains("gif", ignoreCase = true) -> "gif"
            contentType.contains("webp", ignoreCase = true) -> "webp"
            contentType.contains("jpeg", ignoreCase = true) || contentType.contains("jpg", ignoreCase = true) -> "jpg"
            else ->
                url.substringAfterLast('.', "jpg").lowercase().takeIf { it in setOf("png", "gif", "webp", "jpg", "jpeg") }?.let {
                    if (it == "jpeg") "jpg" else it
                } ?: "jpg"
        }
}
