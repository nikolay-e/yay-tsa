package dev.yaytsa.infra.llm

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

// Stub pending a local LLM integration. The external Anthropic client was removed;
// returning null keeps the adaptive queue running on ML-only recommendations until
// a local model is wired into complete().
@Component
class LlmClient {
    private val log = LoggerFactory.getLogger(javaClass)

    fun complete(prompt: String): String? {
        log.debug("LLM client is a stub; received prompt of {} chars, no completion produced", prompt.length)
        return null
    }
}
