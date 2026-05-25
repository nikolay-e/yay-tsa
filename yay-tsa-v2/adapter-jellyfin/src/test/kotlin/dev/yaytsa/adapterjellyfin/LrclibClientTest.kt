package dev.yaytsa.adapterjellyfin

import com.fasterxml.jackson.databind.ObjectMapper
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets

class LrclibClientTest :
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
            val bytes = body.toByteArray(StandardCharsets.UTF_8)
            sendResponseHeaders(status, bytes.size.toLong())
            responseBody.use { it.write(bytes) }
        }

        "returns synced lyrics from /api/get" {
            withStub({ exchange ->
                if (exchange.requestURI.path == "/api/get") {
                    exchange.respond(200, """{"syncedLyrics":"[00:01.00] hello","plainLyrics":"hello"}""")
                } else {
                    exchange.respond(404, "")
                }
            }) { baseUrl ->
                val client = LrclibClient(baseUrl, ObjectMapper())
                client.fetch("Song", "Artist", "Album", 180) shouldBe "[00:01.00] hello"
            }
        }

        "falls back to /api/search when /api/get is 404" {
            withStub({ exchange ->
                when (exchange.requestURI.path) {
                    "/api/get" -> exchange.respond(404, """{"code":404}""")
                    "/api/search" -> exchange.respond(200, """[{"plainLyrics":"plain words"}]""")
                    else -> exchange.respond(404, "")
                }
            }) { baseUrl ->
                val client = LrclibClient(baseUrl, ObjectMapper())
                client.fetch("Song", "Artist", null, null) shouldBe "plain words"
            }
        }

        "prefers synced from search over a plain-only exact match" {
            withStub({ exchange ->
                when (exchange.requestURI.path) {
                    "/api/get" -> exchange.respond(200, """{"plainLyrics":"plain only"}""")
                    "/api/search" ->
                        exchange.respond(
                            200,
                            """[{"plainLyrics":"other plain"},{"syncedLyrics":"[00:02.00] synced!","plainLyrics":"x"}]""",
                        )
                    else -> exchange.respond(404, "")
                }
            }) { baseUrl ->
                val client = LrclibClient(baseUrl, ObjectMapper())
                client.fetch("Song", "Artist", "Album", 200) shouldBe "[00:02.00] synced!"
            }
        }

        "returns null when artist is blank" {
            withStub({ exchange -> exchange.respond(500, "should not be called") }) { baseUrl ->
                val client = LrclibClient(baseUrl, ObjectMapper())
                client.fetch("Song", "", null, null) shouldBe null
            }
        }

        "returns null when nothing matches" {
            withStub({ exchange ->
                when (exchange.requestURI.path) {
                    "/api/get" -> exchange.respond(404, "")
                    "/api/search" -> exchange.respond(200, "[]")
                    else -> exchange.respond(404, "")
                }
            }) { baseUrl ->
                val client = LrclibClient(baseUrl, ObjectMapper())
                client.fetch("Song", "Artist", null, 200) shouldBe null
            }
        }
    })
