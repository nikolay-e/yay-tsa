package dev.yaytsa.infra.llm

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlin.random.Random

@Component
class LlmClient(
    @Value("\${yaytsa.llm.enabled:false}") private val enabled: Boolean,
    @Value("\${yaytsa.llm.api-key:#{null}}") private val apiKey: String?,
    @Value("\${yaytsa.llm.model:GPT-5.4 Mini}") private val model: String,
    @Value("\${yaytsa.llm.base-url:http://litellm.litellm.svc.cluster.local:8080}") private val baseUrl: String,
    @Value("\${yaytsa.llm.max-tokens:1024}") private val maxTokens: Int,
    @Value("\${yaytsa.llm.system-prompt:#{null}}") private val systemPrompt: String?,
    private val objectMapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    private val httpClient =
        HttpClient
            .newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build()

    fun complete(
        prompt: String,
        user: String? = null,
    ): String? {
        if (!enabled) {
            log.debug("LLM disabled; skipping completion ({} char prompt)", prompt.length)
            return null
        }
        if (apiKey.isNullOrBlank()) {
            log.warn("LLM enabled but no API key configured; adaptive DJ rewrite skipped (queue unchanged)")
            return null
        }

        val body = buildRequestBody(prompt, user)
        val response = post(body) ?: return null
        return parseCompletion(response)
    }

    private fun buildRequestBody(
        prompt: String,
        user: String?,
    ): String {
        val root = objectMapper.createObjectNode()
        root.put("model", model)
        root.put("max_tokens", maxTokens)
        if (!user.isNullOrBlank()) {
            root.put("user", user)
        }
        val messages = objectMapper.createArrayNode()
        if (!systemPrompt.isNullOrBlank()) {
            val systemMessage = objectMapper.createObjectNode()
            systemMessage.put("role", "system")
            systemMessage.put("content", systemPrompt)
            messages.add(systemMessage)
        }
        val userMessage = objectMapper.createObjectNode()
        userMessage.put("role", "user")
        userMessage.put("content", prompt)
        messages.add(userMessage)
        root.set<ArrayNode>("messages", messages)
        return objectMapper.writeValueAsString(root)
    }

    private fun post(body: String): String? {
        val uri = URI.create(baseUrl.trimEnd('/') + "/v1/chat/completions")
        var attempt = 0
        var lastError = "unknown"
        while (attempt <= MAX_RETRIES) {
            val request =
                HttpRequest
                    .newBuilder(uri)
                    .header("Authorization", "Bearer $apiKey")
                    .header("content-type", "application/json")
                    .timeout(Duration.ofSeconds(REQUEST_TIMEOUT_SECONDS))
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build()
            val result = runCatching { httpClient.send(request, HttpResponse.BodyHandlers.ofString()) }
            val response = result.getOrNull()
            if (response == null) {
                lastError = result.exceptionOrNull()?.message ?: "request failed"
                log.warn("LLM request failed: {}", lastError)
            } else {
                when (val code = response.statusCode()) {
                    in 200..299 -> return response.body()
                    429, in 500..599 -> {
                        lastError = "HTTP $code"
                        log.debug("LLM transient {} on attempt {}", code, attempt + 1)
                    }
                    else -> {
                        log.warn("LLM returned {}: {}", code, response.body().take(ERROR_BODY_LIMIT))
                        return null
                    }
                }
            }
            attempt++
            if (attempt <= MAX_RETRIES) backoff(attempt)
        }
        log.warn("LLM unavailable after {} retries: {}; adaptive DJ rewrite skipped (queue unchanged)", MAX_RETRIES, lastError)
        return null
    }

    private fun parseCompletion(responseBody: String): String? =
        try {
            val root = objectMapper.readTree(responseBody)
            val text =
                root
                    .path("choices")
                    .firstOrNull()
                    ?.path("message")
                    ?.path("content")
                    ?.asText()
                    ?.ifBlank { null }
            if (text == null) {
                log.warn("LLM response had no message content; adaptive DJ rewrite skipped (queue unchanged)")
            }
            text
        } catch (e: Exception) {
            log.warn("Failed to parse LLM response; adaptive DJ rewrite skipped (queue unchanged): {}", e.message)
            null
        }

    private fun backoff(attempt: Int) {
        val base = BACKOFF_BASE_MS * (1L shl (attempt - 1).coerceAtMost(BACKOFF_MAX_SHIFT))
        val jitter = Random.nextLong(base / 2 + 1)
        try {
            Thread.sleep(base + jitter)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw e
        }
    }

    companion object {
        private const val MAX_RETRIES = 2
        private const val REQUEST_TIMEOUT_SECONDS = 30L
        private const val BACKOFF_BASE_MS = 500L
        private const val BACKOFF_MAX_SHIFT = 5
        private const val ERROR_BODY_LIMIT = 500
    }
}
