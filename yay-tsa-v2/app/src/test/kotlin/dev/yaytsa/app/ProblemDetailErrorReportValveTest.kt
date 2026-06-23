package dev.yaytsa.app

import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.ImportAutoConfiguration
import org.springframework.boot.autoconfigure.web.servlet.ServletWebServerFactoryAutoConfiguration
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import
import java.net.Socket
import kotlin.test.assertTrue

// Connector-level rejections (illegal char in the request target) are produced by Tomcat's HTTP
// parser before any servlet runs, so MockMvc cannot reproduce them — only a real embedded Tomcat on
// a real port does. A raw socket is required because high-level HTTP clients percent-encode or
// reject the illegal ']' client-side. The context is deliberately minimal (web server + the valve,
// no datasource/MPD/cache) to avoid resource collisions with the MockMvc suite in the shared JVM.
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    classes = [ProblemDetailErrorReportValveTest.WebServerOnly::class],
)
class ProblemDetailErrorReportValveTest {
    @Configuration
    @ImportAutoConfiguration(ServletWebServerFactoryAutoConfiguration::class)
    @Import(TomcatErrorValveConfig::class)
    class WebServerOnly

    @LocalServerPort
    private var port: Int = 0

    @Test
    fun `connector-level 400 from an illegal path char returns problem+json not html`() {
        val rawRequest =
            "GET /bad]path HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                "Connection: close\r\n\r\n"

        val response =
            Socket("127.0.0.1", port).use { socket ->
                socket.getOutputStream().apply {
                    write(rawRequest.toByteArray(Charsets.US_ASCII))
                    flush()
                }
                socket.getInputStream().bufferedReader(Charsets.US_ASCII).readText()
            }

        val statusLine = response.lineSequence().firstOrNull().orEmpty()
        assertTrue(statusLine.contains("400"), "expected a 400 status line, got: $statusLine")
        assertTrue(
            response.lowercase().contains("content-type: application/problem+json"),
            "expected application/problem+json, full response:\n$response",
        )
    }
}
