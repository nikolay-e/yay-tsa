package dev.yaytsa.worker.metadata

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.slf4j.LoggerFactory
import java.net.URLEncoder
import java.net.http.HttpClient
import java.nio.charset.StandardCharsets

class ArtistImageSourceClient(
    userAgent: String,
    rateLimiter: RateLimiter,
    private val musicBrainzBaseUrl: String,
    private val wikidataBaseUrl: String = "https://www.wikidata.org/wiki",
    private val commonsApiUrl: String = "https://commons.wikimedia.org/w/api.php",
) {
    private val log = LoggerFactory.getLogger(javaClass)

    private val http =
        ProviderHttp(
            "Artist image source",
            userAgent,
            rateLimiter,
            ProviderHttp.TransientFailurePolicy.RETURN_NULL,
            HttpClient.Version.HTTP_1_1,
        )

    private val mapper =
        jacksonObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

    fun fetchArtistImage(musicbrainzId: String): CoverArt? =
        runCatching {
            val wikidataQid = resolveWikidataQid(musicbrainzId) ?: return null
            val commonsFile = resolveCommonsFilename(wikidataQid) ?: return null
            val imageUrl = resolveCommonsImageUrl(commonsFile) ?: return null
            http.getImage(imageUrl)
        }.getOrElse {
            log.warn("Artist image lookup for mbid {} failed, falling back: {}", musicbrainzId, it.message)
            null
        }

    private fun resolveWikidataQid(musicbrainzId: String): String? {
        val body = getJson("$musicBrainzBaseUrl/artist/$musicbrainzId?inc=url-rels&fmt=json") ?: return null
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
        val body = getJson("$wikidataBaseUrl/Special:EntityData/$wikidataQid.json") ?: return null
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
            getJson("$commonsApiUrl?action=query&prop=imageinfo&iiprop=url&titles=$title&format=json")
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

    private fun getJson(url: String): String? = http.getString(url, accept = "application/json")
}
