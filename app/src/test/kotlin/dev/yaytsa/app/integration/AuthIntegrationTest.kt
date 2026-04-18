package dev.yaytsa.app.integration

import dev.yaytsa.application.auth.AuthQueries
import dev.yaytsa.application.auth.AuthUseCases
import dev.yaytsa.domain.auth.CreateUser
import dev.yaytsa.domain.auth.UpdateProfile
import dev.yaytsa.shared.AggregateVersion
import dev.yaytsa.shared.CommandContext
import dev.yaytsa.shared.CommandResult
import dev.yaytsa.shared.IdempotencyKey
import dev.yaytsa.shared.ProtocolId
import dev.yaytsa.shared.UserId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders
import java.time.Instant
import java.util.UUID

class AuthIntegrationTest : HttpIntegrationTestBase() {
    @Autowired
    lateinit var authQueries: AuthQueries

    @Autowired
    lateinit var authUseCases: AuthUseCases

    // --- Public endpoints ---

    @Test
    fun `health endpoint returns 200`() {
        val result = get("/System/Ping")
        assertEquals(200, result.response.status)
    }

    @Test
    fun `System Info Public returns 200 without auth`() {
        val result = get("/System/Info/Public")
        assertEquals(200, result.response.status)
        assertTrue(result.response.contentAsString.contains("ServerName"))
    }

    @Test
    fun `unauthenticated GET Items returns 401`() {
        val result = get("/Items")
        assertEquals(401, result.response.status)
    }

    @Test
    fun `unauthenticated GET artists returns 401`() {
        val result = get("/Artists")
        assertEquals(401, result.response.status)
    }

    // --- Jellyfin login flow ---

    @Test
    fun `login with wrong password returns 401`() {
        val password = "correctpassword"
        val hash =
            org.springframework.security.crypto.bcrypt.BCrypt
                .hashpw(
                    password,
                    org.springframework.security.crypto.bcrypt.BCrypt
                        .gensalt(),
                )
        val username = "login-wrong-pw-${UUID.randomUUID().toString().take(8)}"
        seedUser(username, hash)

        val result =
            mockMvc
                .perform(
                    MockMvcRequestBuilders
                        .post("/Users/AuthenticateByName")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"Username":"$username","Pw":"wrongpassword"}"""),
                ).andReturn()
        assertEquals(401, result.response.status)
    }

    @Test
    fun `login with nonexistent user returns 401`() {
        val result =
            mockMvc
                .perform(
                    MockMvcRequestBuilders
                        .post("/Users/AuthenticateByName")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"Username":"nonexistent-${UUID.randomUUID()}","Pw":"anything"}"""),
                ).andReturn()
        assertEquals(401, result.response.status)
    }

    @Test
    fun `login with correct password returns token`() {
        val password = "mypassword123"
        val hash =
            org.springframework.security.crypto.bcrypt.BCrypt
                .hashpw(
                    password,
                    org.springframework.security.crypto.bcrypt.BCrypt
                        .gensalt(),
                )
        val username = "login-ok-${UUID.randomUUID().toString().take(8)}"
        seedUser(username, hash)

        val result =
            mockMvc
                .perform(
                    MockMvcRequestBuilders
                        .post("/Users/AuthenticateByName")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"Username":"$username","Pw":"$password"}"""),
                ).andReturn()
        assertEquals(200, result.response.status)
        val body = result.response.contentAsString
        assertTrue(body.contains("AccessToken"))
        assertTrue(body.contains("User"))
    }

    // --- Full token lifecycle (catches double-hash regression) ---

    @Test
    fun `full lifecycle - create user, login, use token, update user, re-authenticate`() {
        val password = "lifecycle-pass"
        val hash =
            org.springframework.security.crypto.bcrypt.BCrypt
                .hashpw(
                    password,
                    org.springframework.security.crypto.bcrypt.BCrypt
                        .gensalt(),
                )
        val username = "lifecycle-${UUID.randomUUID().toString().take(8)}"
        val userId = seedUser(username, hash)

        // Step 1: Login and get token
        val loginResult =
            mockMvc
                .perform(
                    MockMvcRequestBuilders
                        .post("/Users/AuthenticateByName")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"Username":"$username","Pw":"$password"}"""),
                ).andReturn()
        assertEquals(200, loginResult.response.status, "Login should succeed")
        val token = objectMapper.readTree(loginResult.response.contentAsString).path("AccessToken").asText()
        assertFalse(token.isNullOrBlank(), "Token should not be blank")

        // Step 2: Use token to access protected endpoint
        val itemsResult =
            mockMvc
                .perform(
                    MockMvcRequestBuilders
                        .get("/Items")
                        .header("X-Emby-Token", token)
                        .param("IncludeItemTypes", "MusicArtist"),
                ).andReturn()
        assertEquals(200, itemsResult.response.status, "Token should work for Items")

        // Step 3: Update user profile (triggers save cycle — catches double-hash)
        val user = authQueries.findUser(UserId(userId))!!
        val updateCmd = UpdateProfile(UserId(userId), "New Name", null)
        val updateCtx = CommandContext(UserId(userId), ProtocolId("JELLYFIN"), Instant.now(), IdempotencyKey(UUID.randomUUID().toString()), user.version)
        val updateResult = authUseCases.execute(updateCmd, updateCtx)
        assertTrue(updateResult is CommandResult.Success, "Profile update should succeed")

        // Step 4: Re-authenticate with SAME token (catches double-hash corruption)
        val reAuthResult =
            mockMvc
                .perform(
                    MockMvcRequestBuilders
                        .get("/Items")
                        .header("X-Emby-Token", token)
                        .param("IncludeItemTypes", "MusicArtist"),
                ).andReturn()
        assertEquals(200, reAuthResult.response.status, "Token must still work after user update (catches double-hash)")
    }

    // --- Actuator auth ---

    @Test
    fun `actuator metrics requires authentication`() {
        val result = get("/manage/metrics")
        assertEquals(401, result.response.status)
    }

    @Test
    fun `actuator health is public`() {
        val result = get("/manage/health")
        assertEquals(200, result.response.status)
    }

    // --- Helper ---

    private fun seedUser(
        username: String,
        passwordHash: String,
    ): String {
        val userId = UUID.randomUUID().toString()
        val uid = UserId(userId)
        val cmd = CreateUser(uid, username, passwordHash, null, null, false)
        val ctx = CommandContext(uid, ProtocolId("JELLYFIN"), Instant.now(), IdempotencyKey(UUID.randomUUID().toString()), AggregateVersion.INITIAL)
        val result = authUseCases.execute(cmd, ctx)
        assertTrue(result is CommandResult.Success, "User creation should succeed")
        return userId
    }
}
