package dev.yaytsa.app.integration

import dev.yaytsa.application.auth.AuthUseCases
import dev.yaytsa.domain.auth.ApiTokenId
import dev.yaytsa.domain.auth.CreateApiToken
import dev.yaytsa.domain.auth.CreateUser
import dev.yaytsa.shared.AggregateVersion
import dev.yaytsa.shared.CommandContext
import dev.yaytsa.shared.DeviceId
import dev.yaytsa.shared.IdempotencyKey
import dev.yaytsa.shared.ProtocolId
import dev.yaytsa.shared.UserId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders
import java.time.Instant
import java.util.UUID

class RemediationSecurityIntegrationTest : HttpIntegrationTestBase() {
    @Autowired
    lateinit var authUseCases: AuthUseCases

    private data class Seeded(
        val id: String,
        val token: String,
    )

    private fun seedUser(
        prefix: String,
        isAdmin: Boolean = false,
    ): Seeded {
        val id = UUID.randomUUID().toString()
        val token = UUID.randomUUID().toString()
        val uid = UserId(id)
        val now = Instant.now()
        authUseCases.execute(
            CreateUser(uid, "$prefix-${id.take(8)}", "testpassword", "Test", null, isAdmin),
            CommandContext(uid, ProtocolId("JELLYFIN"), now, IdempotencyKey(UUID.randomUUID().toString()), AggregateVersion.INITIAL),
        )
        authUseCases.execute(
            CreateApiToken(uid, ApiTokenId(UUID.randomUUID().toString()), token, DeviceId("test"), "Test", null),
            CommandContext(uid, ProtocolId("JELLYFIN"), now, IdempotencyKey(UUID.randomUUID().toString()), AggregateVersion(1)),
        )
        return Seeded(id, token)
    }

    private fun putPreferences(
        userId: String,
        token: String,
    ) = mockMvc
        .perform(
            MockMvcRequestBuilders
                .put("/v1/users/$userId/preferences")
                .header("Authorization", "Bearer $token")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"hardRules":"{}","softPrefs":"{}","djStyle":"{}","redLines":[]}"""),
        ).andReturn()

    @Test
    fun `user cannot read another user's preferences`() {
        val a = seedUser("alice")
        val b = seedUser("bob")
        assertEquals(403, get("/v1/users/${b.id}/preferences", a.token).response.status)
        assertEquals(200, get("/v1/users/${a.id}/preferences", a.token).response.status)
    }

    @Test
    fun `user cannot overwrite another user's preferences`() {
        val a = seedUser("alice")
        val b = seedUser("bob")
        assertEquals(403, putPreferences(b.id, a.token).response.status)
        assertEquals(204, putPreferences(a.id, a.token).response.status)
    }

    @Test
    fun `non-admin cannot read another user's profile but admin can`() {
        val a = seedUser("alice")
        val b = seedUser("bob")
        val admin = seedUser("admin", isAdmin = true)
        assertEquals(403, get("/Users/${b.id}", a.token).response.status)
        assertEquals(200, get("/Users/${a.id}", a.token).response.status, "self read allowed")
        assertEquals(200, get("/Users/${b.id}", admin.token).response.status, "admin read allowed")
    }

    @Test
    fun `admin deactivation rejects the user's token immediately despite the validation cache`() {
        val victim = seedUser("victim")
        val admin = seedUser("admin", isAdmin = true)
        assertEquals(200, get("/Items", victim.token).response.status, "token works before deactivation")

        assertEquals(204, delete("/Admin/Users/${victim.id}", admin.token).response.status)

        assertEquals(401, get("/Items", victim.token).response.status, "deactivated user must be rejected within the cache TTL")
    }

    @Test
    fun `admin password reset revokes the user's existing tokens immediately`() {
        val victim = seedUser("victim")
        val admin = seedUser("admin", isAdmin = true)
        assertEquals(200, get("/Items", victim.token).response.status, "token works before reset")

        assertEquals(200, post("/Admin/Users/${victim.id}/ResetPassword", emptyMap<String, Any>(), admin.token).response.status)

        assertEquals(401, get("/Items", victim.token).response.status, "old session must die on password reset")
    }
}
