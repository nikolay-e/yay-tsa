package dev.yaytsa.app.integration

import dev.yaytsa.application.auth.AuthUseCases
import dev.yaytsa.domain.auth.ApiTokenId
import dev.yaytsa.domain.auth.CreateApiToken
import dev.yaytsa.domain.auth.CreateUser
import dev.yaytsa.domain.auth.DeviceId
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
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders
import java.time.Instant
import java.util.UUID

class JellyfinApiIntegrationTest : HttpIntegrationTestBase() {
    @Autowired
    lateinit var authUseCases: AuthUseCases

    private lateinit var userId: String
    private lateinit var token: String
    private lateinit var username: String

    @BeforeEach
    fun seedUser() {
        userId = UUID.randomUUID().toString()
        token = UUID.randomUUID().toString()
        username = "jf-test-${UUID.randomUUID().toString().take(8)}"
        val uid = UserId(userId)
        val now = Instant.now()

        val createCmd = CreateUser(uid, username, "testpassword", "Test", null, false)
        val ctx = CommandContext(uid, ProtocolId("JELLYFIN"), now, IdempotencyKey(UUID.randomUUID().toString()), AggregateVersion.INITIAL)
        authUseCases.execute(createCmd, ctx)

        val tokenCmd = CreateApiToken(uid, ApiTokenId(UUID.randomUUID().toString()), token, DeviceId("test"), "Test", null)
        val tokenCtx = CommandContext(uid, ProtocolId("JELLYFIN"), now, IdempotencyKey(UUID.randomUUID().toString()), AggregateVersion(1))
        authUseCases.execute(tokenCmd, tokenCtx)
    }

    @Test
    fun `GET Users by id with X-Emby-Token returns user`() {
        val result =
            mockMvc
                .perform(
                    MockMvcRequestBuilders
                        .get("/Users/$userId")
                        .header("X-Emby-Token", token),
                ).andReturn()
        assertEquals(200, result.response.status)
        assertTrue(result.response.contentAsString.contains("Id"))
        assertTrue(result.response.contentAsString.contains(userId))
    }

    @Test
    fun `GET Items with X-Emby-Token returns 200`() {
        val result =
            mockMvc
                .perform(
                    MockMvcRequestBuilders
                        .get("/Items")
                        .header("X-Emby-Token", token)
                        .param("IncludeItemTypes", "MusicArtist")
                        .param("Limit", "10"),
                ).andReturn()
        assertEquals(200, result.response.status)
        assertTrue(result.response.contentAsString.contains("Items"))
        assertTrue(result.response.contentAsString.contains("TotalRecordCount"))
    }

    @Test
    fun `GET Items returns PascalCase fields`() {
        val result =
            mockMvc
                .perform(
                    MockMvcRequestBuilders
                        .get("/Items")
                        .header("X-Emby-Token", token)
                        .param("IncludeItemTypes", "MusicArtist"),
                ).andReturn()
        val body = result.response.contentAsString
        assertTrue(body.contains("Items"))
        assertTrue(body.contains("TotalRecordCount"))
        assertTrue(body.contains("StartIndex"))
    }

    @Test
    fun `Jellyfin auth with api_key query param works`() {
        val result =
            mockMvc
                .perform(
                    MockMvcRequestBuilders
                        .get("/Items")
                        .param("api_key", token)
                        .param("IncludeItemTypes", "MusicArtist"),
                ).andReturn()
        assertEquals(200, result.response.status)
    }

    @Test
    fun `POST Sessions Playing returns 204`() {
        val result =
            mockMvc
                .perform(
                    MockMvcRequestBuilders
                        .post("/Sessions/Playing")
                        .header("X-Emby-Token", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"ItemId":"${UUID.randomUUID()}","PositionTicks":0,"IsPaused":false}"""),
                ).andReturn()
        assertEquals(204, result.response.status)
    }

    @Test
    fun `POST Sessions Playing Stopped returns 204`() {
        val result =
            mockMvc
                .perform(
                    MockMvcRequestBuilders
                        .post("/Sessions/Playing/Stopped")
                        .header("X-Emby-Token", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"ItemId":"${UUID.randomUUID()}","PositionTicks":50000000}"""),
                ).andReturn()
        assertEquals(204, result.response.status)
    }

    @Test
    fun `GET System Info Public works without auth`() {
        val result = get("/System/Info/Public")
        assertEquals(200, result.response.status)
        assertTrue(result.response.contentAsString.contains("ServerName"))
    }

    @Test
    fun `GET Items without auth returns 401`() {
        val result = get("/Items")
        assertEquals(401, result.response.status)
    }
}
