package dev.yaytsa.infra.llm

import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.errors.BadRequestException
import com.anthropic.models.messages.MessageCreateParams
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference

@Component
class LlmClient(
    @Value("\${yaytsa.llm.api-key:#{null}}") private val apiKey: String?,
    @Value("\${yaytsa.llm.model}") private val model: String,
    @Value("\${yaytsa.llm.credit-circuit-breaker-minutes:60}") private val creditCircuitBreakerMinutes: Long,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val client by lazy { apiKey?.let { AnthropicOkHttpClient.builder().apiKey(it).build() } }

    // When Anthropic returns "credit balance too low", a per-session scheduler tick
    // re-hits the API ~once per session per minute and floods the log. We trip a
    // time-based circuit breaker on that specific 400 so subsequent calls short-circuit
    // until [creditCircuitBreakerMinutes] elapses — caller still receives null, but no
    // API call and no error log.
    private val coldUntil = AtomicReference<Instant>(Instant.MIN)

    fun complete(prompt: String): String? {
        val sdkClient = client
        if (sdkClient == null) {
            log.debug("LLM API key not configured, skipping")
            return null
        }
        val now = Instant.now()
        if (now.isBefore(coldUntil.get())) return null
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
        } catch (e: BadRequestException) {
            if (e.message?.contains("credit balance", ignoreCase = true) == true) {
                val coldFor = Duration.ofMinutes(creditCircuitBreakerMinutes)
                val previous = coldUntil.getAndSet(now.plus(coldFor))
                if (previous.isBefore(now)) {
                    log.warn(
                        "LLM credit balance exhausted; suppressing further calls for {} minutes",
                        creditCircuitBreakerMinutes,
                    )
                }
            } else {
                log.error("LLM API call failed", e)
            }
            null
        } catch (e: Exception) {
            log.error("LLM API call failed", e)
            null
        }
    }
}
