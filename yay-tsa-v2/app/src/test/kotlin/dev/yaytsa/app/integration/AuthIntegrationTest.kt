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
    fun `unauthenticated request returns RFC7807 problem detail body`() {
        val result = get("/Items")
        assertEquals(401, result.response.status)
        assertTrue(
            result.response.contentType?.startsWith(MediaType.APPLICATION_PROBLEM_JSON_VALUE) == true,
            "401 must be application/problem+json, was ${result.response.contentType}",
        )
        val body = result.response.contentAsString
        assertTrue(body.contains("\"status\":401"), "body=$body")
        assertTrue(body.contains("\"title\":\"Unauthorized\""), "body=$body")
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
    fun `login with non-JSON content type returns 415 not 500`() {
        val result =
            mockMvc
                .perform(
                    MockMvcRequestBuilders
                        .post("/Users/AuthenticateByName")
                        .contentType(MediaType.TEXT_PLAIN)
                        .content("not json"),
                ).andReturn()
        assertEquals(415, result.response.status)
        assertTrue(
            result.response.contentType?.startsWith(MediaType.APPLICATION_PROBLEM_JSON_VALUE) == true,
            "415 must be application/problem+json, was ${result.response.contentType}",
        )
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

    // --- Admin create-user (GUI sends PascalCase body) ---

    @Test
    fun `admin creates user via PascalCase GUI payload and the new user can log in`() {
        val adminPassword = "admin-create-pass"
        val adminHash =
            org.springframework.security.crypto.bcrypt.BCrypt
                .hashpw(
                    adminPassword,
                    org.springframework.security.crypto.bcrypt.BCrypt
                        .gensalt(),
                )
        val adminName = "admin-${UUID.randomUUID().toString().take(8)}"
        seedUser(adminName, adminHash, isAdmin = true)

        val loginResult =
            mockMvc
                .perform(
                    MockMvcRequestBuilders
                        .post("/Users/AuthenticateByName")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"Username":"$adminName","Pw":"$adminPassword"}"""),
                ).andReturn()
        assertEquals(200, loginResult.response.status, "admin login should succeed")
        val token = objectMapper.readTree(loginResult.response.contentAsString).path("AccessToken").asText()

        val newUsername = "created-${UUID.randomUUID().toString().take(8)}"
        val createResult =
            mockMvc
                .perform(
                    MockMvcRequestBuilders
                        .post("/Admin/Users")
                        .header("Authorization", "Bearer $token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"Username":"$newUsername","DisplayName":"Created User","IsAdmin":false}"""),
                ).andReturn()
        assertEquals(
            200,
            createResult.response.status,
            "create-user must accept the PascalCase body the GUI sends; body=${createResult.response.contentAsString}",
        )
        val createBody = objectMapper.readTree(createResult.response.contentAsString)
        assertEquals(newUsername, createBody.path("user").path("Username").asText())
        val initialPassword = createBody.path("initialPassword").asText()
        assertFalse(initialPassword.isNullOrBlank(), "initial password must be returned")

        val newUserLogin =
            mockMvc
                .perform(
                    MockMvcRequestBuilders
                        .post("/Users/AuthenticateByName")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"Username":"$newUsername","Pw":"$initialPassword"}"""),
                ).andReturn()
        assertEquals(200, newUserLogin.response.status, "newly created user should log in with the returned initial password")
    }

    @Test
    fun `non-admin cannot create user`() {
        val password = "regular-pass"
        val hash =
            org.springframework.security.crypto.bcrypt.BCrypt
                .hashpw(
                    password,
                    org.springframework.security.crypto.bcrypt.BCrypt
                        .gensalt(),
                )
        val username = "regular-${UUID.randomUUID().toString().take(8)}"
        seedUser(username, hash, isAdmin = false)

        val loginResult =
            mockMvc
                .perform(
                    MockMvcRequestBuilders
                        .post("/Users/AuthenticateByName")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"Username":"$username","Pw":"$password"}"""),
                ).andReturn()
        val token = objectMapper.readTree(loginResult.response.contentAsString).path("AccessToken").asText()

        val createResult =
            mockMvc
                .perform(
                    MockMvcRequestBuilders
                        .post("/Admin/Users")
                        .header("Authorization", "Bearer $token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"Username":"hijack-${UUID.randomUUID().toString().take(8)}","IsAdmin":true}"""),
                ).andReturn()
        assertEquals(403, createResult.response.status, "non-admin must not create users")
    }

    // --- Self-service change password ---

    @Test
    fun `change password with correct current password reissues a working token and revokes the old one`() {
        val oldPassword = "old-pass-123"
        val oldHash =
            org.springframework.security.crypto.bcrypt.BCrypt
                .hashpw(
                    oldPassword,
                    org.springframework.security.crypto.bcrypt.BCrypt
                        .gensalt(),
                )
        val username = "chpw-ok-${UUID.randomUUID().toString().take(8)}"
        seedUser(username, oldHash)

        val loginResult =
            mockMvc
                .perform(
                    MockMvcRequestBuilders
                        .post("/Users/AuthenticateByName")
                        .with(uniqueClientIp())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"Username":"$username","Pw":"$oldPassword"}"""),
                ).andReturn()
        assertEquals(200, loginResult.response.status)
        val oldToken = objectMapper.readTree(loginResult.response.contentAsString).path("AccessToken").asText()

        val newPassword = "brand-new-pass-456"
        val changeResult =
            mockMvc
                .perform(
                    MockMvcRequestBuilders
                        .post("/Users/Password")
                        .header("Authorization", "Bearer $oldToken")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"CurrentPw":"$oldPassword","NewPw":"$newPassword"}"""),
                ).andReturn()
        assertEquals(200, changeResult.response.status, "change password should succeed; body=${changeResult.response.contentAsString}")
        val newToken = objectMapper.readTree(changeResult.response.contentAsString).path("AccessToken").asText()
        assertFalse(newToken.isNullOrBlank(), "a reissued token must be returned")

        val withNewToken =
            mockMvc
                .perform(
                    MockMvcRequestBuilders
                        .get("/Items")
                        .header("X-Emby-Token", newToken)
                        .param("IncludeItemTypes", "MusicArtist"),
                ).andReturn()
        assertEquals(200, withNewToken.response.status, "the reissued token must authenticate")

        val withOldToken =
            mockMvc
                .perform(
                    MockMvcRequestBuilders
                        .get("/Items")
                        .header("X-Emby-Token", oldToken)
                        .param("IncludeItemTypes", "MusicArtist"),
                ).andReturn()
        assertEquals(401, withOldToken.response.status, "the old token must be revoked after a password change")
    }

    @Test
    fun `change password with wrong current password is rejected and does not change the password`() {
        val password = "real-pass-789"
        val hash =
            org.springframework.security.crypto.bcrypt.BCrypt
                .hashpw(
                    password,
                    org.springframework.security.crypto.bcrypt.BCrypt
                        .gensalt(),
                )
        val username = "chpw-wrong-${UUID.randomUUID().toString().take(8)}"
        seedUser(username, hash)

        val loginResult =
            mockMvc
                .perform(
                    MockMvcRequestBuilders
                        .post("/Users/AuthenticateByName")
                        .with(uniqueClientIp())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"Username":"$username","Pw":"$password"}"""),
                ).andReturn()
        val token = objectMapper.readTree(loginResult.response.contentAsString).path("AccessToken").asText()

        val changeResult =
            mockMvc
                .perform(
                    MockMvcRequestBuilders
                        .post("/Users/Password")
                        .header("Authorization", "Bearer $token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"CurrentPw":"not-the-password","NewPw":"whatever-new-pass"}"""),
                ).andReturn()
        assertEquals(403, changeResult.response.status, "wrong current password must be rejected with 403")

        val stillWorks =
            mockMvc
                .perform(
                    MockMvcRequestBuilders
                        .get("/Items")
                        .header("X-Emby-Token", token)
                        .param("IncludeItemTypes", "MusicArtist"),
                ).andReturn()
        assertEquals(200, stillWorks.response.status, "a rejected change must not revoke the caller's token")

        val originalStillValid =
            mockMvc
                .perform(
                    MockMvcRequestBuilders
                        .post("/Users/AuthenticateByName")
                        .with(uniqueClientIp())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"Username":"$username","Pw":"$password"}"""),
                ).andReturn()
        assertEquals(200, originalStillValid.response.status, "the original password must be unchanged")
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
        isAdmin: Boolean = false,
    ): String {
        val userId = UUID.randomUUID().toString()
        val uid = UserId(userId)
        val cmd = CreateUser(uid, username, passwordHash, null, null, isAdmin)
        val ctx = CommandContext(uid, ProtocolId("JELLYFIN"), Instant.now(), IdempotencyKey(UUID.randomUUID().toString()), AggregateVersion.INITIAL)
        val result = authUseCases.execute(cmd, ctx)
        assertTrue(result is CommandResult.Success, "User creation should succeed")
        return userId
    }
}
