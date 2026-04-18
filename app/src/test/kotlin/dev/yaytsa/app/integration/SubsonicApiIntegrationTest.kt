package dev.yaytsa.app.integration

import dev.yaytsa.application.auth.AuthUseCases
import dev.yaytsa.domain.auth.CreateUser
import dev.yaytsa.shared.AggregateVersion
import dev.yaytsa.shared.CommandContext
import dev.yaytsa.shared.IdempotencyKey
import dev.yaytsa.shared.ProtocolId
import dev.yaytsa.shared.UserId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders
import java.time.Instant
import java.util.UUID

class SubsonicApiIntegrationTest : HttpIntegrationTestBase() {
    @Autowired
    lateinit var authUseCases: AuthUseCases

    private lateinit var username: String
    private lateinit var password: String

    @BeforeEach
    fun seedUser() {
        username = "subsonic-${UUID.randomUUID().toString().take(8)}"
        password = "testpass123"
        val uid = UserId(UUID.randomUUID().toString())
        val now = Instant.now()

        val createCmd = CreateUser(uid, username, password, null, null, false)
        val ctx = CommandContext(uid, ProtocolId("JELLYFIN"), now, IdempotencyKey(UUID.randomUUID().toString()), AggregateVersion.INITIAL)
        authUseCases.execute(createCmd, ctx)
    }

    @Test
    fun `ping returns ok`() {
        val result =
            mockMvc
                .perform(
                    MockMvcRequestBuilders
                        .get("/rest/ping")
                        .param("u", username)
                        .param("p", password)
                        .param("v", "1.16.1")
                        .param("c", "test"),
                ).andReturn()
        assertEquals(200, result.response.status)
    }

    @Test
    fun `ping returns XML by default`() {
        val result =
            mockMvc
                .perform(
                    MockMvcRequestBuilders
                        .get("/rest/ping")
                        .param("u", username)
                        .param("p", password)
                        .param("v", "1.16.1")
                        .param("c", "test"),
                ).andReturn()
        assertTrue(result.response.contentType?.contains("xml") ?: false)
    }

    @Test
    fun `ping with f=json returns JSON`() {
        val result =
            mockMvc
                .perform(
                    MockMvcRequestBuilders
                        .get("/rest/ping")
                        .param("u", username)
                        .param("p", password)
                        .param("v", "1.16.1")
                        .param("c", "test")
                        .param("f", "json"),
                ).andReturn()
        assertTrue(result.response.contentType?.contains("json") ?: false)
    }

    @Test
    fun `getArtists without auth returns 401`() {
        val result =
            mockMvc
                .perform(
                    MockMvcRequestBuilders.get("/rest/getArtists"),
                ).andReturn()
        assertEquals(401, result.response.status)
    }

    @Test
    fun `getLicense returns valid`() {
        val result =
            mockMvc
                .perform(
                    MockMvcRequestBuilders
                        .get("/rest/getLicense")
                        .param("u", username)
                        .param("p", password)
                        .param("v", "1.16.1")
                        .param("c", "test"),
                ).andReturn()
        assertEquals(200, result.response.status)
    }
}
