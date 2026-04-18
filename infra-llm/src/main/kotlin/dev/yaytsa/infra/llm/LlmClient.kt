package dev.yaytsa.infra.llm

import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

@Component
class LlmClient(
    @Value("\${yaytsa.llm.api-key:#{null}}") private val apiKey: String?,
    @Value("\${yaytsa.llm.model:claude-sonnet-4-20250514}") private val model: String,
    @Value("\${yaytsa.llm.api-url:https://api.anthropic.com/v1/messages}") private val apiUrl: String,
    private val objectMapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()

    fun complete(prompt: String): String? {
        if (apiKey == null) {
            log.debug("LLM API key not configured, skipping")
            return null
        }
        try {
            val body =
                objectMapper.writeValueAsString(
                    mapOf(
                        "model" to model,
                        "max_tokens" to 1024,
                        "messages" to listOf(mapOf("role" to "user", "content" to prompt)),
                    ),
                )
            val request =
                HttpRequest
                    .newBuilder()
                    .uri(URI.create(apiUrl))
                    .header("x-api-key", apiKey)
                    .header("anthropic-version", "2023-06-01")
                    .header("content-type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .timeout(Duration.ofSeconds(30))
                    .build()

            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() != 200) {
                log.warn("LLM API error: {} {}", response.statusCode(), response.body().take(200))
                return null
            }
            val json = objectMapper.readTree(response.body())
            return json
                .path("content")
                .firstOrNull()
                ?.path("text")
                ?.asText()
        } catch (e: Exception) {
            log.error("LLM API call failed", e)
            return null
        }
    }
}
