package dev.yaytsa.app.integration

import dev.yaytsa.application.auth.AuthUseCases
import dev.yaytsa.domain.auth.CreateUser
import dev.yaytsa.shared.AggregateVersion
import dev.yaytsa.shared.CommandContext
import dev.yaytsa.shared.CommandResult
import dev.yaytsa.shared.IdempotencyKey
import dev.yaytsa.shared.ProtocolId
import dev.yaytsa.shared.UserId
import io.micrometer.core.instrument.MeterRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.crypto.bcrypt.BCrypt
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders
import java.time.Instant
import java.util.UUID

class AuthRateLimitIntegrationTest : HttpIntegrationTestBase() {
    @Autowired
    lateinit var authUseCases: AuthUseCases

    @Autowired
    lateinit var meterRegistry: MeterRegistry

    private fun seedUser(
        username: String,
        password: String,
    ): String {
        val userId = UUID.randomUUID().toString()
        val uid = UserId(userId)
        val cmd = CreateUser(uid, username, BCrypt.hashpw(password, BCrypt.gensalt()), null, null, false)
        val ctx = CommandContext(uid, ProtocolId("JELLYFIN"), Instant.now(), IdempotencyKey(UUID.randomUUID().toString()), AggregateVersion.INITIAL)
        val result = authUseCases.execute(cmd, ctx)
        assertTrue(result is CommandResult.Success, "User creation should succeed")
        return userId
    }

    private fun jellyfinLogin(
        username: String,
        password: String,
        ip: String,
    ): MockHttpServletResponse =
        mockMvc
            .perform(
                MockMvcRequestBuilders
                    .post("/Users/AuthenticateByName")
                    .with { req ->
                        req.remoteAddr = ip
                        req
                    }.contentType(MediaType.APPLICATION_JSON)
                    .content("""{"Username":"$username","Pw":"$password"}"""),
            ).andReturn()
            .response

    private fun subsonicPing(
        username: String,
        password: String,
        ip: String,
    ): MockHttpServletResponse =
        mockMvc
            .perform(
                MockMvcRequestBuilders
                    .get("/rest/ping")
                    .with { req ->
                        req.remoteAddr = ip
                        req
                    }.param("u", username)
                    .param("p", password),
            ).andReturn()
            .response

    private fun throttledCount(
        protocol: String,
        scope: String,
    ): Double =
        meterRegistry
            .find("yaytsa.auth.throttled")
            .tags("protocol", protocol, "scope", scope)
            .counter()
            ?.count() ?: 0.0

    @Test
    fun `jellyfin failed logins from one IP hit the limit with 429 and Retry-After`() {
        val ip = "198.51.100.10"
        val username = "brute-jf-${UUID.randomUUID().toString().take(8)}"
        seedUser(username, "correct-password")
        val throttledBefore = throttledCount("jellyfin", "ip")

        repeat(10) {
            assertEquals(401, jellyfinLogin(username, "wrong-password-$it", ip).status)
        }
        val rejected = jellyfinLogin(username, "wrong-password-again", ip)

        assertEquals(429, rejected.status)
        val retryAfter = rejected.getHeader("Retry-After")
        assertNotNull(retryAfter, "429 must carry Retry-After")
        val retryAfterSeconds = retryAfter!!.toLong()
        assertTrue(retryAfterSeconds in 1..60, "Retry-After should be within the refill window, was $retryAfterSeconds")
        assertTrue(
            rejected.contentAsString.contains("Too Many Requests"),
            "throttled response must be problem+json: ${rejected.contentAsString}",
        )
        assertTrue(throttledCount("jellyfin", "ip") > throttledBefore, "yaytsa.auth.throttled{jellyfin,ip} must increment")
    }

    @Test
    fun `successful jellyfin logins never consume the failure budget`() {
        val ip = "198.51.100.20"
        val username = "ok-jf-${UUID.randomUUID().toString().take(8)}"
        seedUser(username, "correct-password")

        repeat(12) {
            assertEquals(200, jellyfinLogin(username, "correct-password", ip).status)
        }
    }

    @Test
    fun `one username brute-forced across many IPs is throttled by the per-username bucket`() {
        val username = "brute-user-${UUID.randomUUID().toString().take(8)}"
        seedUser(username, "correct-password")

        repeat(10) {
            assertEquals(401, jellyfinLogin(username, "wrong-password", "203.0.113.${it + 100}").status)
        }
        val rejected = jellyfinLogin(username, "wrong-password", "203.0.113.250")

        assertEquals(429, rejected.status)
        assertNotNull(rejected.getHeader("Retry-After"))
        assertTrue(throttledCount("jellyfin", "username") > 0.0, "yaytsa.auth.throttled{jellyfin,username} must increment")
    }

    @Test
    fun `subsonic failed credential floods from one IP hit the limit with 429 and Retry-After`() {
        val ip = "198.51.100.30"
        val username = "brute-sub-${UUID.randomUUID().toString().take(8)}"
        seedUser(username, "correct-password")

        repeat(10) {
            val response = subsonicPing(username, "wrong-password", ip)
            assertEquals(200, response.status)
            assertTrue(response.contentAsString.contains("code=\"40\""), "expected Subsonic code 40: ${response.contentAsString}")
        }
        val rejected = subsonicPing(username, "wrong-password", ip)

        assertEquals(429, rejected.status)
        assertNotNull(rejected.getHeader("Retry-After"))
        assertTrue(throttledCount("subsonic", "ip") > 0.0, "yaytsa.auth.throttled{subsonic,ip} must increment")
    }

    @Test
    fun `successful subsonic requests never consume the failure budget`() {
        val ip = "198.51.100.40"
        val username = "ok-sub-${UUID.randomUUID().toString().take(8)}"
        seedUser(username, "correct-password")

        repeat(12) {
            val response = subsonicPing(username, "correct-password", ip)
            assertEquals(200, response.status)
            assertTrue(response.contentAsString.contains("status=\"ok\""), "expected ok envelope: ${response.contentAsString}")
        }
    }
}
