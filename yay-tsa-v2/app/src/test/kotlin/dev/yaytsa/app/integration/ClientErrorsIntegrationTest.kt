package dev.yaytsa.app.integration

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.micrometer.core.instrument.MeterRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders

class ClientErrorsIntegrationTest : HttpIntegrationTestBase() {
    @Autowired
    lateinit var meterRegistry: MeterRegistry

    private fun postRaw(
        body: String,
        contentType: MediaType = MediaType.TEXT_PLAIN,
    ): Int =
        mockMvc
            .perform(
                MockMvcRequestBuilders
                    .post("/v1/client-errors")
                    .contentType(contentType)
                    .content(body),
            ).andReturn()
            .response.status

    private fun counter(
        category: String,
        type: String,
        version: String,
    ): Double =
        meterRegistry
            .find("yaytsa.client.errors")
            .tags("category", category, "type", type, "version", version)
            .counter()
            ?.count() ?: 0.0

    private fun captureClientErrorLogs(action: () -> Unit): List<String> {
        val logger = LoggerFactory.getLogger("client-error") as Logger
        val appender = ListAppender<ILoggingEvent>()
        appender.start()
        logger.addAppender(appender)
        try {
            action()
        } finally {
            logger.detachAppender(appender)
        }
        return appender.list.map { it.formattedMessage }
    }

    @Test
    fun `unauthenticated POST client-errors returns 204`() {
        val body = """{"category":"runtime","type":"TypeError","message":"boom","appVersion":"main-1a2b3c4"}"""
        assertEquals(204, postRaw(body))
    }

    @Test
    fun `valid report increments counter with its bounded tags`() {
        val before = counter("runtime", "TypeError", "main-deadbee")
        val body = """{"category":"runtime","type":"TypeError","message":"x","appVersion":"main-deadbee"}"""
        assertEquals(204, postRaw(body))
        assertEquals(before + 1.0, counter("runtime", "TypeError", "main-deadbee"))
    }

    @Test
    fun `unknown category and type are coerced to other for the counter tag`() {
        val before = counter("other", "other", "unknown")
        val body =
            """{"category":"' OR 1=1; DROP","type":"WeirdNovelErrorClass","message":"x","appVersion":"not a version!!"}"""
        assertEquals(204, postRaw(body))
        assertEquals(before + 1.0, counter("other", "other", "unknown"))
    }

    @Test
    fun `secrets in message and stack are redacted before logging`() {
        val secret = "abcdefghijklmnopqrstuvwxyz0123456789ABCDEF"
        val body =
            """{"category":"network","type":"NetworkError",""" +
                """"message":"failed https://yay-tsa.com/Audio/1/stream?api_key=SUPERSECRETVALUE",""" +
                """"stack":"at f Bearer $secret token=$secret","appVersion":"main-1"}"""
        val lines = captureClientErrorLogs { assertEquals(204, postRaw(body)) }
        assertEquals(1, lines.size)
        val line = lines.single()
        assertFalse(line.contains("SUPERSECRETVALUE"), "api_key value leaked: $line")
        assertFalse(line.contains(secret), "bearer/high-entropy token leaked: $line")
        assertTrue(line.contains("[REDACTED]"), "expected redaction marker: $line")
    }

    @Test
    fun `control chars and newlines are stripped so the log line stays single-line`() {
        val body =
            "{\"category\":\"runtime\",\"type\":\"Error\"," +
                "\"message\":\"line1\\nline2\\u001b[31mforged\\u2028tail\",\"appVersion\":\"main-1\"}"
        val lines = captureClientErrorLogs { assertEquals(204, postRaw(body)) }
        val line = lines.single()
        assertFalse(line.contains('\n'), "newline survived: $line")
        assertFalse(line.contains('\u001B'), "ANSI escape survived: \$line")
        assertFalse(line.contains('\u2028'), "unicode line separator survived: \$line")
    }

    @Test
    fun `malformed body returns 204 and increments dropped counter`() {
        val before =
            meterRegistry
                .find("yaytsa.client.errors.dropped")
                .tags("reason", "malformed")
                .counter()
                ?.count() ?: 0.0
        assertEquals(204, postRaw("this is not json at all"))
        val after =
            meterRegistry
                .find("yaytsa.client.errors.dropped")
                .tags("reason", "malformed")
                .counter()
        assertNotNull(after)
        assertEquals(before + 1.0, after!!.count())
    }
}
