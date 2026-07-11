package dev.yaytsa.adaptershared

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import dev.yaytsa.application.ml.port.EmbeddingPort
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

@Component
class HttpEmbeddingClient(
    @Value("\${yaytsa.embedding.enabled:false}") private val enabled: Boolean,
    @Value("\${yaytsa.embedding.base-url:http://audio-separator.yay-tsa-production.svc.cluster.local:8000}")
    private val baseUrl: String,
) : EmbeddingPort {
    private val log = LoggerFactory.getLogger(javaClass)
    private val objectMapper: ObjectMapper = jacksonObjectMapper()

    private val httpClient: HttpClient =
        HttpClient
            .newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(5))
            .build()

    override fun isAvailable(): Boolean = enabled

    override fun encodeText(query: String): FloatArray? {
        if (!enabled) {
            log.debug("Text embedding disabled; semantic search returns empty")
            return null
        }
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return null

        val payload = objectMapper.writeValueAsString(mapOf("text" to trimmed))
        val request =
            HttpRequest
                .newBuilder()
                .uri(URI.create("${baseUrl.trimEnd('/')}/api/v1/embed/text"))
                .timeout(Duration.ofSeconds(REQUEST_TIMEOUT_SECONDS))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .build()

        val response =
            runCatching { httpClient.send(request, HttpResponse.BodyHandlers.ofString()) }
                .getOrElse {
                    log.warn("Text embedding request failed: {}", it.message)
                    return null
                }
        if (response.statusCode() !in 200..299) {
            log.warn("Text embedding service returned {}", response.statusCode())
            return null
        }
        val vector =
            runCatching {
                val node = objectMapper.readTree(response.body()).get("embedding") ?: return null
                FloatArray(node.size()) { node.get(it).floatValue() }
            }.getOrElse {
                log.warn("Failed to parse text embedding response: {}", it.message)
                return null
            }
        if (vector.size != CLAP_DIM) {
            log.warn("Text embedding has unexpected dimension {} (expected {}); degrading to lexical", vector.size, CLAP_DIM)
            return null
        }
        return vector
    }

    private companion object {
        const val REQUEST_TIMEOUT_SECONDS = 10L
        const val CLAP_DIM = 512
    }
}
