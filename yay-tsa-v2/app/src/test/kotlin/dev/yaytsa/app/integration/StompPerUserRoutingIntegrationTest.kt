package dev.yaytsa.app.integration

import dev.yaytsa.application.auth.AuthUseCases
import dev.yaytsa.domain.auth.ApiTokenId
import dev.yaytsa.domain.auth.CreateApiToken
import dev.yaytsa.domain.auth.CreateUser
import dev.yaytsa.infranotifications.NotificationPublisher
import dev.yaytsa.shared.AggregateVersion
import dev.yaytsa.shared.CommandContext
import dev.yaytsa.shared.DeviceId
import dev.yaytsa.shared.IdempotencyKey
import dev.yaytsa.shared.ProtocolId
import dev.yaytsa.shared.UserId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.lang.Nullable
import org.springframework.messaging.converter.StringMessageConverter
import org.springframework.messaging.simp.stomp.StompFrameHandler
import org.springframework.messaging.simp.stomp.StompHeaders
import org.springframework.messaging.simp.stomp.StompSession
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.web.socket.client.standard.StandardWebSocketClient
import org.springframework.web.socket.messaging.WebSocketStompClient
import java.lang.reflect.Type
import java.time.Instant
import java.util.UUID
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.TimeUnit

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class StompPerUserRoutingIntegrationTest {
    @Autowired
    lateinit var authUseCases: AuthUseCases

    @Autowired
    lateinit var publisher: NotificationPublisher

    @LocalServerPort
    var port: Int = 0

    companion object {
        @DynamicPropertySource
        @JvmStatic
        fun props(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { HttpIntegrationTestBase.postgres.jdbcUrl }
            registry.add("spring.datasource.username") { HttpIntegrationTestBase.postgres.username }
            registry.add("spring.datasource.password") { HttpIntegrationTestBase.postgres.password }
            registry.add("spring.flyway.enabled") { "false" }
            registry.add("spring.jpa.hibernate.ddl-auto") { "none" }
            registry.add("yaytsa.library.music-path") { System.getProperty("java.io.tmpdir") }
            registry.add("yaytsa.mpd.enabled") { "false" }
            registry.add("yaytsa.llm.enabled") { "false" }
            registry.add("yaytsa.ml.enabled") { "false" }
            registry.add("yaytsa.karaoke.enabled") { "false" }
            // RANDOM_PORT is a distinct Spring context; disabling Spring cache + bucket4j here
            // avoids creating a second JCache CacheManager that would collide with the MOCK base's.
            registry.add("spring.cache.type") { "none" }
            registry.add("bucket4j.enabled") { "false" }
        }
    }

    private fun seedUserToken(prefix: String): String {
        val id = UUID.randomUUID().toString()
        val token = UUID.randomUUID().toString()
        val uid = UserId(id)
        val now = Instant.now()
        authUseCases.execute(
            CreateUser(uid, "$prefix-${id.take(8)}", "testpassword", "Test", null, false),
            CommandContext(uid, ProtocolId("JELLYFIN"), now, IdempotencyKey(UUID.randomUUID().toString()), AggregateVersion.INITIAL),
        )
        authUseCases.execute(
            CreateApiToken(uid, ApiTokenId(UUID.randomUUID().toString()), token, DeviceId("test"), "Test", null),
            CommandContext(uid, ProtocolId("JELLYFIN"), now, IdempotencyKey(UUID.randomUUID().toString()), AggregateVersion(1)),
        )
        return token
    }

    private fun connect(token: String): Pair<StompSession, (String) -> LinkedBlockingDeque<String>> {
        val client = WebSocketStompClient(StandardWebSocketClient())
        client.messageConverter = StringMessageConverter()
        val connectHeaders = StompHeaders()
        connectHeaders.add("X-Emby-Token", token)
        val session =
            client
                .connectAsync(
                    "ws://localhost:$port/ws",
                    org.springframework.web.socket
                        .WebSocketHttpHeaders(),
                    connectHeaders,
                    object : StompSessionHandlerAdapter() {},
                ).get(5, TimeUnit.SECONDS)
        val subscribe = { destination: String ->
            val received = LinkedBlockingDeque<String>()
            session.subscribe(
                destination,
                object : StompFrameHandler {
                    override fun getPayloadType(headers: StompHeaders): Type = String::class.java

                    override fun handleFrame(
                        headers: StompHeaders,
                        @Nullable payload: Any?,
                    ) {
                        received.add(payload as? String ?: "")
                    }
                },
            )
            received
        }
        return session to subscribe
    }

    @Test
    fun `a user-scoped notification reaches only that user, not another`() {
        val tokenA = seedUserToken("alice")
        val userIdA = subjectOf(tokenA)
        val tokenB = seedUserToken("bob")

        val (sessionA, subA) = connect(tokenA)
        val (sessionB, subB) = connect(tokenB)
        try {
            val queueA = subA("/user/queue/preferences")
            val queueB = subB("/user/queue/preferences")
            Thread.sleep(300) // let SUBSCRIBE frames register

            publisher.publish("preferences", """{"userId":"$userIdA"}""")

            val gotA = queueA.poll(3, TimeUnit.SECONDS)
            assertEquals("""{"userId":"$userIdA"}""", gotA, "the owning user must receive their preference event")
            assertNull(queueB.poll(1, TimeUnit.SECONDS), "another user must NOT receive it")
        } finally {
            sessionA.disconnect()
            sessionB.disconnect()
        }
    }

    @Test
    fun `a notification with no userId broadcasts to every subscriber`() {
        val (sessionA, subA) = connect(seedUserToken("alice"))
        val (sessionB, subB) = connect(seedUserToken("bob"))
        try {
            val queueA = subA("/topic/library")
            val queueB = subB("/topic/library")
            Thread.sleep(300)

            publisher.publish("library", """{"entityId":"album-1"}""")

            assertEquals("""{"entityId":"album-1"}""", queueA.poll(3, TimeUnit.SECONDS))
            assertEquals("""{"entityId":"album-1"}""", queueB.poll(3, TimeUnit.SECONDS))
        } finally {
            sessionA.disconnect()
            sessionB.disconnect()
        }
    }

    // The auth interceptor sets the STOMP principal to the user id, so we recover it from the token.
    private fun subjectOf(token: String): String {
        // token -> user via the same query the interceptor uses; expose through authUseCases.
        return authUseCases.findByApiToken(token)!!.id.value
    }
}
