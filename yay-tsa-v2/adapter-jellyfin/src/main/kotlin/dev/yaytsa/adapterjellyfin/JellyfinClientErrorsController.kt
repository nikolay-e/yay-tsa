package dev.yaytsa.adapterjellyfin

import com.fasterxml.jackson.databind.ObjectMapper
import io.micrometer.core.instrument.MeterRegistry
import jakarta.servlet.http.HttpServletRequest
import org.slf4j.LoggerFactory
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.security.Principal

@RestController
@RequestMapping("/v1")
class JellyfinClientErrorsController(
    private val objectMapper: ObjectMapper,
    private val meterRegistry: MeterRegistry,
) {
    private val log = LoggerFactory.getLogger("client-error")

    @PostMapping("/client-errors")
    fun ingest(
        principal: Principal?,
        request: HttpServletRequest,
    ): ResponseEntity<Void> {
        val rawBody = readBounded(request)
        if (rawBody == null) {
            meterRegistry.counter("yaytsa.client.errors.dropped", "reason", "oversize").increment()
            return ResponseEntity.noContent().build()
        }

        val parsed =
            try {
                objectMapper.readValue(rawBody, object : com.fasterxml.jackson.core.type.TypeReference<Map<String, Any?>>() {})
            } catch (ex: Exception) {
                log.warn("{}", malformedLogLine(principal))
                meterRegistry.counter("yaytsa.client.errors.dropped", "reason", "malformed").increment()
                meterRegistry
                    .counter("yaytsa.client.errors", "category", "other", "type", "other", "version", "unknown")
                    .increment()
                return ResponseEntity.noContent().build()
            }

        val fullType = sanitize(stringField(parsed["type"], TYPE_LOG_LIMIT))
        val catTag = coerceCategory(stringField(parsed["category"], CATEGORY_LIMIT))
        val typeTag = coerceType(stringField(parsed["type"], CATEGORY_LIMIT))
        val verTag = coerceVersion(stringField(parsed["appVersion"], VERSION_LIMIT))

        val line =
            linkedMapOf<String, Any?>(
                "src" to "client_error",
                "category" to catTag,
                "type" to fullType,
                "version" to verTag,
                "route" to sanitize(stringField(parsed["route"], ROUTE_LIMIT)),
                "status" to intField(parsed["status"]),
                "mediaError" to intField(parsed["mediaError"]),
                "ua" to sanitize(stringField(parsed["ua"], UA_LIMIT)),
                "sid" to sanitize(stringField(parsed["sessionId"], CATEGORY_LIMIT)),
                "fp" to sanitize(stringField(parsed["fingerprint"], CATEGORY_LIMIT)),
                "user" to sanitize(principal?.name),
                "msg" to sanitize(stringField(parsed["message"], MESSAGE_LIMIT)),
                "stack" to sanitize(stringField(parsed["stack"], STACK_LIMIT)),
            )

        log.info("{}", objectMapper.writeValueAsString(line))
        meterRegistry
            .counter("yaytsa.client.errors", "category", catTag, "type", typeTag, "version", verTag)
            .increment()
        return ResponseEntity.noContent().build()
    }

    private fun readBounded(request: HttpServletRequest): String? {
        val buffer = ByteArray(MAX_BODY + 1)
        var total = 0
        request.inputStream.use { input ->
            while (total <= MAX_BODY) {
                val read = input.read(buffer, total, buffer.size - total)
                if (read <= 0) break
                total += read
            }
        }
        if (total > MAX_BODY) return null
        return String(buffer, 0, total, Charsets.UTF_8)
    }

    private fun malformedLogLine(principal: Principal?): String =
        objectMapper.writeValueAsString(
            linkedMapOf<String, Any?>(
                "src" to "client_error",
                "category" to "other",
                "type" to "malformed",
                "version" to "unknown",
                "user" to principal?.name,
            ),
        )

    private fun stringField(
        value: Any?,
        limit: Int,
    ): String? {
        val s = value as? String ?: return null
        return if (s.length > limit) s.substring(0, limit) else s
    }

    private fun intField(value: Any?): Int? =
        when (value) {
            is Number -> value.toInt()
            is String -> value.toIntOrNull()
            else -> null
        }

    private fun coerceCategory(value: String?): String = if (value in ALLOWED_CATEGORIES) value!! else "other"

    private fun coerceType(value: String?): String = if (value in ALLOWED_TYPES) value!! else "other"

    private fun coerceVersion(value: String?): String =
        value
            ?.takeIf { VERSION_PATTERN.matches(it) }
            ?.let { if (it.length > VERSION_LIMIT) it.substring(0, VERSION_LIMIT) else it }
            ?: "unknown"

    private fun sanitize(value: String?): String? {
        if (value == null) return null
        val redacted = redactSecrets(value)
        return CONTROL_CHARS.replace(redacted, " ")
    }

    private fun redactSecrets(value: String): String {
        var out = value
        for ((pattern, replacement) in SECRET_PATTERNS) {
            out = pattern.replace(out, replacement)
        }
        out = HIGH_ENTROPY.replace(out, "[REDACTED]")
        return out
    }

    companion object {
        private const val MAX_BODY = 16 * 1024
        private const val MESSAGE_LIMIT = 1024
        private const val STACK_LIMIT = 4096
        private const val ROUTE_LIMIT = 200
        private const val TYPE_LOG_LIMIT = 100
        private const val UA_LIMIT = 200
        private const val VERSION_LIMIT = 32
        private const val CATEGORY_LIMIT = 64

        private val ALLOWED_CATEGORIES = setOf("runtime", "promise", "react", "network", "audio", "sw")
        private val ALLOWED_TYPES =
            setOf(
                "TypeError",
                "RangeError",
                "ReferenceError",
                "SyntaxError",
                "NetworkError",
                "AbortError",
                "NotSupportedError",
                "NotAllowedError",
                "Error",
            )

        private val VERSION_PATTERN = Regex("^[A-Za-z0-9._-]{1,32}$")

        private val SECRET_PATTERNS =
            listOf(
                Regex("(?i)\\b(api_key|token|password|access_token|accesstoken)\\s*=\\s*[^\\s&\"']+") to "$1=[REDACTED]",
                Regex("(?i)\\bbearer\\s+[A-Za-z0-9._\\-]+") to "Bearer [REDACTED]",
                Regex("(?i)\\b(x-emby-token|x-mediabrowser-token|token|accesstoken|access_token)\\s*:\\s*\"?[^\\s&\",}]+\"?") to "$1: [REDACTED]",
                Regex("(?i)\"(token|accesstoken|access_token|password|api_key)\"\\s*:\\s*\"[^\"]*\"") to "\"$1\":\"[REDACTED]\"",
            )

        private val HIGH_ENTROPY = Regex("[A-Za-z0-9_-]{32,}")

        private val CONTROL_CHARS = Regex("[\\u0000-\\u001F\\u007F\\u2028\\u2029]")
    }
}
