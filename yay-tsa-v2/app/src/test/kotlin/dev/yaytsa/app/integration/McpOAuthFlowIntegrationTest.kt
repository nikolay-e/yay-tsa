package dev.yaytsa.app.integration

import dev.yaytsa.application.auth.AuthUseCases
import dev.yaytsa.domain.auth.CreateUser
import dev.yaytsa.shared.AggregateVersion
import dev.yaytsa.shared.CommandContext
import dev.yaytsa.shared.CommandResult
import dev.yaytsa.shared.IdempotencyKey
import dev.yaytsa.shared.ProtocolId
import dev.yaytsa.shared.UserId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders
import java.security.MessageDigest
import java.time.Instant
import java.util.Base64
import java.util.UUID

class McpOAuthFlowIntegrationTest : HttpIntegrationTestBase() {
    @Autowired
    lateinit var authUseCases: AuthUseCases

    private val callbackUri = "https://claude.ai/api/mcp/auth_callback"

    @Test
    fun `authorization server metadata is served on the unstripped well-known path`() {
        val result = get("/.well-known/oauth-authorization-server")
        assertEquals(200, result.response.status)
        val body = objectMapper.readTree(result.response.contentAsString)
        assertEquals("https://yay-tsa.com", body["issuer"].asText())
        assertEquals("https://yay-tsa.com/api/oauth/authorize", body["authorization_endpoint"].asText())
        assertEquals("https://yay-tsa.com/api/oauth/token", body["token_endpoint"].asText())
        assertEquals("https://yay-tsa.com/api/oauth/register", body["registration_endpoint"].asText())
        assertEquals("S256", body["code_challenge_methods_supported"][0].asText())
    }

    @Test
    fun `protected resource metadata points at the mcp resource`() {
        for (path in listOf("/.well-known/oauth-protected-resource", "/.well-known/oauth-protected-resource/api/mcp")) {
            val result = get(path)
            assertEquals(200, result.response.status, "path=$path")
            val body = objectMapper.readTree(result.response.contentAsString)
            assertEquals("https://yay-tsa.com/api/mcp", body["resource"].asText())
            assertEquals("https://yay-tsa.com", body["authorization_servers"][0].asText())
        }
    }

    @Test
    fun `unauthenticated mcp request advertises resource metadata in www-authenticate`() {
        val result =
            mockMvc
                .perform(
                    MockMvcRequestBuilders
                        .post("/mcp")
                        .with(uniqueClientIp())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}"""),
                ).andReturn()
        assertEquals(401, result.response.status)
        val header = result.response.getHeader("WWW-Authenticate")
        assertNotNull(header)
        assertTrue(
            header!!.contains("resource_metadata=\"https://yay-tsa.com/.well-known/oauth-protected-resource/api/mcp\""),
            "header=$header",
        )
    }

    @Test
    fun `registration rejects non-https redirect uris`() {
        val result =
            mockMvc
                .perform(
                    MockMvcRequestBuilders
                        .post("/oauth/register")
                        .with(uniqueClientIp())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"redirect_uris":["http://evil.example/cb"],"client_name":"Bad"}"""),
                ).andReturn()
        assertEquals(400, result.response.status)
        assertTrue(result.response.contentAsString.contains("invalid_redirect_uri"))
    }

    @Test
    fun `full authorization code flow with pkce yields a working mcp token`() {
        val password = "oauth-pass-${UUID.randomUUID().toString().take(8)}"
        val username = "oauth-user-${UUID.randomUUID().toString().take(8)}"
        seedUser(username, password)

        val clientId = registerClient()

        val verifier = "v".repeat(43) + UUID.randomUUID().toString().take(8)
        val challenge =
            Base64
                .getUrlEncoder()
                .withoutPadding()
                .encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))

        val formPage =
            mockMvc
                .perform(
                    MockMvcRequestBuilders
                        .get("/oauth/authorize")
                        .with(uniqueClientIp())
                        .param("client_id", clientId)
                        .param("redirect_uri", callbackUri)
                        .param("response_type", "code")
                        .param("state", "st-123")
                        .param("code_challenge", challenge)
                        .param("code_challenge_method", "S256"),
                ).andReturn()
        assertEquals(200, formPage.response.status)
        assertTrue(formPage.response.contentType!!.startsWith(MediaType.TEXT_HTML_VALUE))
        assertTrue(formPage.response.contentAsString.contains("name=\"code_challenge\""))
        assertEquals("DENY", formPage.response.getHeader("X-Frame-Options"))
        // The consent POST 302s to the registered redirect origin; Chrome enforces form-action
        // against the redirect, so the origin must be whitelisted or the whole flow is blocked.
        val csp = formPage.response.getHeader("Content-Security-Policy")!!
        assertTrue(csp.contains("form-action 'self' https://claude.ai"), "csp=$csp")

        val location = authorizeAndGetLocation(clientId, challenge, username, password, state = "st-123")
        assertTrue(location.startsWith("$callbackUri?"), "location=$location")
        assertTrue(location.contains("state=st-123"), "location=$location")
        val code = Regex("[?&]code=([^&]+)").find(location)!!.groupValues[1]

        val wrongVerifier = exchangeToken(clientId, code, "w".repeat(50))
        assertEquals(400, wrongVerifier.first)
        assertTrue(wrongVerifier.second.contains("invalid_grant"))

        val secondLocation = authorizeAndGetLocation(clientId, challenge, username, password, state = null)
        val freshCode = Regex("[?&]code=([^&]+)").find(secondLocation)!!.groupValues[1]

        val success = exchangeToken(clientId, freshCode, verifier)
        assertEquals(200, success.first, "body=${success.second}")
        val accessToken = objectMapper.readTree(success.second)["access_token"].asText()

        val replay = exchangeToken(clientId, freshCode, verifier)
        assertEquals(400, replay.first)
        assertTrue(replay.second.contains("invalid_grant"))

        val mcpResult =
            mockMvc
                .perform(
                    MockMvcRequestBuilders
                        .post("/mcp")
                        .with(uniqueClientIp())
                        .header("Authorization", "Bearer $accessToken")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}"""),
                ).andReturn()
        assertEquals(200, mcpResult.response.status)
        assertTrue(mcpResult.response.contentAsString.contains("search_library"))
    }

    @Test
    fun `authorize with wrong password re-renders the login form without a code`() {
        val username = "oauth-badpw-${UUID.randomUUID().toString().take(8)}"
        seedUser(username, "correct-password-123")
        val clientId = registerClient()

        val result =
            mockMvc
                .perform(
                    MockMvcRequestBuilders
                        .post("/oauth/authorize")
                        .with(uniqueClientIp())
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("client_id", clientId)
                        .param("redirect_uri", callbackUri)
                        .param("response_type", "code")
                        .param("code_challenge", "c".repeat(43))
                        .param("code_challenge_method", "S256")
                        .param("username", username)
                        .param("password", "wrong-password"),
                ).andReturn()
        assertEquals(401, result.response.status)
        assertTrue(result.response.contentAsString.contains("Invalid username or password"))
    }

    @Test
    fun `authorize with unregistered redirect uri never redirects`() {
        val clientId = registerClient()
        val result =
            mockMvc
                .perform(
                    MockMvcRequestBuilders
                        .get("/oauth/authorize")
                        .with(uniqueClientIp())
                        .param("client_id", clientId)
                        .param("redirect_uri", "https://attacker.example/cb")
                        .param("response_type", "code")
                        .param("code_challenge", "c".repeat(43))
                        .param("code_challenge_method", "S256"),
                ).andReturn()
        assertEquals(400, result.response.status)
    }

    private fun authorizeAndGetLocation(
        clientId: String,
        challenge: String,
        username: String,
        password: String,
        state: String?,
    ): String {
        val builder =
            MockMvcRequestBuilders
                .post("/oauth/authorize")
                .with(uniqueClientIp())
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("client_id", clientId)
                .param("redirect_uri", callbackUri)
                .param("response_type", "code")
                .param("code_challenge", challenge)
                .param("code_challenge_method", "S256")
                .param("username", username)
                .param("password", password)
        if (state != null) builder.param("state", state)
        val result = mockMvc.perform(builder).andReturn()
        assertEquals(302, result.response.status)
        return result.response.getHeader("Location")!!
    }

    private fun registerClient(): String {
        val result =
            mockMvc
                .perform(
                    MockMvcRequestBuilders
                        .post("/oauth/register")
                        .with(uniqueClientIp())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"redirect_uris":["$callbackUri"],"client_name":"Claude"}"""),
                ).andReturn()
        assertEquals(201, result.response.status, "body=${result.response.contentAsString}")
        return objectMapper.readTree(result.response.contentAsString)["client_id"].asText()
    }

    private fun exchangeToken(
        clientId: String,
        code: String,
        verifier: String,
    ): Pair<Int, String> {
        val result =
            mockMvc
                .perform(
                    MockMvcRequestBuilders
                        .post("/oauth/token")
                        .with(uniqueClientIp())
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("grant_type", "authorization_code")
                        .param("code", code)
                        .param("redirect_uri", callbackUri)
                        .param("client_id", clientId)
                        .param("code_verifier", verifier),
                ).andReturn()
        return result.response.status to result.response.contentAsString
    }

    private fun seedUser(
        username: String,
        password: String,
    ): String {
        val hash =
            org.springframework.security.crypto.bcrypt.BCrypt
                .hashpw(
                    password,
                    org.springframework.security.crypto.bcrypt.BCrypt
                        .gensalt(),
                )
        val userId = UUID.randomUUID().toString()
        val uid = UserId(userId)
        val cmd = CreateUser(uid, username, hash, null, null, false)
        val ctx =
            CommandContext(uid, ProtocolId("JELLYFIN"), Instant.now(), IdempotencyKey(UUID.randomUUID().toString()), AggregateVersion.INITIAL)
        val result = authUseCases.execute(cmd, ctx)
        assertTrue(result is CommandResult.Success, "User creation should succeed")
        return userId
    }
}
