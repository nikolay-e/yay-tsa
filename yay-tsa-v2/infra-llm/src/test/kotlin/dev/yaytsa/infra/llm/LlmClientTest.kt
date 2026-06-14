package dev.yaytsa.infra.llm

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
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

private val CHAT_COMPLETION_RESPONSE: String =
    ObjectMapper().let { mapper ->
        val root = mapper.createObjectNode()
        root.put("id", "chatcmpl_test")
        root.put("object", "chat.completion")
        root.put("model", "gpt-5.4-mini")
        val choices = mapper.createArrayNode()
        val choice = mapper.createObjectNode()
        choice.put("index", 0)
        val message = mapper.createObjectNode()
        message.put("role", "assistant")
        message.put("content", QUEUE_EDIT_JSON)
        choice.set<ObjectNode>("message", message)
        choice.put("finish_reason", "stop")
        choices.add(choice)
        root.set<ArrayNode>("choices", choices)
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
            model = "GPT-5.4 Mini",
            baseUrl = baseUrl,
            maxTokens = 1024,
            systemPrompt = null,
            objectMapper = ObjectMapper(),
        )

        "calls the chat-completions API and returns the message content" {
            withStub({ exchange ->
                if (exchange.requestURI.path == "/v1/chat/completions") {
                    exchange.respond(200, CHAT_COMPLETION_RESPONSE)
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
                exchange.respond(200, CHAT_COMPLETION_RESPONSE)
            }) { baseUrl ->
                val response = client(baseUrl).complete("suggest tracks")
                response shouldNotBe null
                val parsed = ObjectMapper().readTree(response)
                parsed.isArray shouldBe true
                parsed[0].path("track_id").asText() shouldBe "11111111-1111-1111-1111-111111111111"
            }
        }

        "sends Bearer auth and a well-formed OpenAI chat body with the end-user id" {
            var seenAuth: String? = null
            var seenBody = ""
            withStub({ exchange ->
                seenAuth = exchange.requestHeaders.getFirst("Authorization")
                seenBody = exchange.requestBody.readBytes().toString(StandardCharsets.UTF_8)
                exchange.respond(200, CHAT_COMPLETION_RESPONSE)
            }) { baseUrl ->
                client(baseUrl).complete("suggest tracks", user = "user-42")
            }
            seenAuth shouldBe "Bearer test-key"
            val body = ObjectMapper().readTree(seenBody)
            body.path("model").asText() shouldBe "GPT-5.4 Mini"
            body.path("user").asText() shouldBe "user-42"
            val lastMessage = body.path("messages").last()
            lastMessage.path("role").asText() shouldBe "user"
            lastMessage.path("content").asText() shouldContain "suggest tracks"
        }

        "omits the user field when no end-user id is provided" {
            var seenBody = ""
            withStub({ exchange ->
                seenBody = exchange.requestBody.readBytes().toString(StandardCharsets.UTF_8)
                exchange.respond(200, CHAT_COMPLETION_RESPONSE)
            }) { baseUrl ->
                client(baseUrl).complete("suggest tracks")
            }
            ObjectMapper().readTree(seenBody).has("user") shouldBe false
        }

        "returns null without calling the API when disabled" {
            val calls = AtomicInteger(0)
            withStub({ exchange ->
                calls.incrementAndGet()
                exchange.respond(200, CHAT_COMPLETION_RESPONSE)
            }) { baseUrl ->
                client(baseUrl, enabled = false).complete("suggest tracks") shouldBe null
            }
            calls.get() shouldBe 0
        }

        "returns null without calling the API when no key is configured" {
            val calls = AtomicInteger(0)
            withStub({ exchange ->
                calls.incrementAndGet()
                exchange.respond(200, CHAT_COMPLETION_RESPONSE)
            }) { baseUrl ->
                client(baseUrl, apiKey = null).complete("suggest tracks") shouldBe null
            }
            calls.get() shouldBe 0
        }

        "returns null on a non-retryable HTTP error (graceful ML-only fallback)" {
            withStub({ exchange ->
                exchange.respond(400, """{"error":{"message":"invalid_request","type":"invalid_request_error"}}""")
            }) { baseUrl ->
                client(baseUrl).complete("suggest tracks") shouldBe null
            }
        }

        "returns null when the response has no message content" {
            withStub({ exchange ->
                exchange.respond(200, """{"id":"chatcmpl","object":"chat.completion","choices":[]}""")
            }) { baseUrl ->
                client(baseUrl).complete("suggest tracks") shouldBe null
            }
        }
    })
