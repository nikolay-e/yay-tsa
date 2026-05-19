package dev.yaytsa.infra.llm

import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.models.messages.MessageCreateParams
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

@Component
class LlmClient(
    @Value("\${yaytsa.llm.api-key:#{null}}") private val apiKey: String?,
    @Value("\${yaytsa.llm.model}") private val model: String,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val client by lazy { apiKey?.let { AnthropicOkHttpClient.builder().apiKey(it).build() } }

    fun complete(prompt: String): String? {
        val sdkClient = client
        if (sdkClient == null) {
            log.debug("LLM API key not configured, skipping")
            return null
        }
        return try {
            val response =
                sdkClient.messages().create(
                    MessageCreateParams
                        .builder()
                        .model(model)
                        .maxTokens(1024L)
                        .addUserMessage(prompt)
                        .build(),
                )
            response
                .content()
                .firstOrNull()
                ?.text()
                ?.orElse(null)
                ?.text()
        } catch (e: Exception) {
            log.error("LLM API call failed", e)
            null
        }
    }
}
