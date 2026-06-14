package dev.yaytsa.infra.llm

import com.fasterxml.jackson.databind.ObjectMapper
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicInteger

private const val QUEUE_EDIT_JSON =
    """[{"track_id":"11111111-1111-1111-1111-111111111111","reason":"matches your taste"}]"""

private val ANTHROPIC_RESPONSE: String =
    ObjectMapper().let { mapper ->
        val root = mapper.createObjectNode()
        root.put("id", "msg_test")
        root.put("type", "message")
        root.put("role", "assistant")
        root.put("model", "claude-haiku-4-5")
        val content = mapper.createArrayNode()
        val block = mapper.createObjectNode()
        block.put("type", "text")
        block.put("text", QUEUE_EDIT_JSON)
        content.add(block)
        root.set<com.fasterxml.jackson.databind.node.ArrayNode>("content", content)
        root.put("stop_reason", "end_turn")
        mapper.writeValueAsString(root)
    }

class LlmClientTest :
    StringSpec({

        fun withStub(
            handler: (HttpExchange) -> Unit,
            block: (String) -> Unit,
        ) {
            val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
            server.createContext("/") { exchange -> handler(exchange) }
            server.start()
            try {
                block("http://127.0.0.1:${server.address.port}")
            } finally {
                server.stop(0)
            }
        }

        fun HttpExchange.respond(
            status: Int,
            body: String,
        ) {
            requestBody.use { it.readBytes() }
            val bytes = body.toByteArray(StandardCharsets.UTF_8)
            sendResponseHeaders(status, bytes.size.toLong())
            responseBody.use { it.write(bytes) }
        }

        fun client(
            baseUrl: String,
            enabled: Boolean = true,
            apiKey: String? = "test-key",
        ) = LlmClient(
            enabled = enabled,
            apiKey = apiKey,
            model = "claude-haiku-4-5",
            baseUrl = baseUrl,
            maxTokens = 1024,
            systemPrompt = null,
            objectMapper = ObjectMapper(),
        )

        "calls the Messages API and returns the text content block" {
            withStub({ exchange ->
                if (exchange.requestURI.path == "/v1/messages") {
                    exchange.respond(200, ANTHROPIC_RESPONSE)
                } else {
                    exchange.respond(404, "")
                }
            }) { baseUrl ->
                val response = client(baseUrl).complete("suggest tracks")
                response shouldBe QUEUE_EDIT_JSON
            }
        }

        "the returned content is parseable by the orchestrator queue-edit parser" {
            withStub({ exchange ->
                exchange.respond(200, ANTHROPIC_RESPONSE)
            }) { baseUrl ->
                val response = client(baseUrl).complete("suggest tracks")
                response shouldNotBe null
                val parsed = ObjectMapper().readTree(response)
                parsed.isArray shouldBe true
                parsed[0].path("track_id").asText() shouldBe "11111111-1111-1111-1111-111111111111"
            }
        }

        "sends Anthropic headers and a well-formed Messages body" {
            var seenApiKey: String? = null
            var seenVersion: String? = null
            var seenBody = ""
            withStub({ exchange ->
                seenApiKey = exchange.requestHeaders.getFirst("x-api-key")
                seenVersion = exchange.requestHeaders.getFirst("anthropic-version")
                seenBody = exchange.requestBody.readBytes().toString(StandardCharsets.UTF_8)
                exchange.respond(200, ANTHROPIC_RESPONSE)
            }) { baseUrl ->
                client(baseUrl).complete("suggest tracks")
            }
            seenApiKey shouldBe "test-key"
            seenVersion shouldBe "2023-06-01"
            val body = ObjectMapper().readTree(seenBody)
            body.path("model").asText() shouldBe "claude-haiku-4-5"
            body.path("messages")[0].path("role").asText() shouldBe "user"
            body.path("messages")[0].path("content").asText() shouldContain "suggest tracks"
        }

        "returns null without calling the API when disabled" {
            val calls = AtomicInteger(0)
            withStub({ exchange ->
                calls.incrementAndGet()
                exchange.respond(200, ANTHROPIC_RESPONSE)
            }) { baseUrl ->
                client(baseUrl, enabled = false).complete("suggest tracks") shouldBe null
            }
            calls.get() shouldBe 0
        }

        "returns null without calling the API when no key is configured" {
            val calls = AtomicInteger(0)
            withStub({ exchange ->
                calls.incrementAndGet()
                exchange.respond(200, ANTHROPIC_RESPONSE)
            }) { baseUrl ->
                client(baseUrl, apiKey = null).complete("suggest tracks") shouldBe null
            }
            calls.get() shouldBe 0
        }

        "returns null on a non-retryable HTTP error (graceful ML-only fallback)" {
            withStub({ exchange ->
                exchange.respond(400, """{"type":"error","error":{"type":"invalid_request_error"}}""")
            }) { baseUrl ->
                client(baseUrl).complete("suggest tracks") shouldBe null
            }
        }

        "returns null when the response has no text content block" {
            withStub({ exchange ->
                exchange.respond(200, """{"id":"msg","type":"message","content":[]}""")
            }) { baseUrl ->
                client(baseUrl).complete("suggest tracks") shouldBe null
            }
        }
    })
