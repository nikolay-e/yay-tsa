package dev.yaytsa.app.integration

import dev.yaytsa.application.auth.AuthUseCases
import dev.yaytsa.application.playlists.PlaylistUseCases
import dev.yaytsa.domain.auth.ApiTokenId
import dev.yaytsa.domain.auth.CreateApiToken
import dev.yaytsa.domain.auth.CreateUser
import dev.yaytsa.domain.playlists.CreatePlaylist
import dev.yaytsa.domain.playlists.PlaylistId
import dev.yaytsa.shared.AggregateVersion
import dev.yaytsa.shared.CommandContext
import dev.yaytsa.shared.DeviceId
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

class AuthorizationBoundariesIntegrationTest : HttpIntegrationTestBase() {
    @Autowired
    lateinit var authUseCases: AuthUseCases

    @Autowired
    lateinit var playlistUseCases: PlaylistUseCases

    @Autowired
    lateinit var jdbc: org.springframework.jdbc.core.JdbcTemplate

    private fun seedTrack(): String {
        val id = UUID.randomUUID()
        jdbc.update(
            "INSERT INTO core_v2_library.entities (id, entity_type, name, sort_name, source_path) VALUES (?,?,?,?,?)",
            id,
            "TRACK",
            "Boundary Track",
            "boundary track",
            "Boundary/Album/$id.flac",
        )
        jdbc.update("INSERT INTO core_v2_library.audio_tracks (entity_id, duration_ms) VALUES (?,?)", id, 120000L)
        return id.toString()
    }

    private data class Seeded(
        val id: String,
        val token: String,
        val deviceId: String,
    )

    private fun seedUser(prefix: String): Seeded {
        val id = UUID.randomUUID().toString()
        val token = UUID.randomUUID().toString()
        val deviceId = "dev-$prefix-${id.take(8)}"
        val uid = UserId(id)
        val now = Instant.now()
        authUseCases.execute(
            CreateUser(uid, "$prefix-${id.take(8)}", "testpassword", "Test", null, false),
            CommandContext(uid, ProtocolId("JELLYFIN"), now, IdempotencyKey(UUID.randomUUID().toString()), AggregateVersion.INITIAL),
        )
        authUseCases.execute(
            CreateApiToken(uid, ApiTokenId(UUID.randomUUID().toString()), token, DeviceId(deviceId), "Test", null),
            CommandContext(uid, ProtocolId("JELLYFIN"), now, IdempotencyKey(UUID.randomUUID().toString()), AggregateVersion(1)),
        )
        return Seeded(id, token, deviceId)
    }

    @Test
    fun `favorites cannot be mutated on behalf of another user`() {
        val a = seedUser("fav-a")
        val b = seedUser("fav-b")
        val trackId = seedTrack()

        assertEquals(403, post("/UserFavoriteItems/$trackId?userId=${b.id}", emptyMap<String, Any>(), a.token).response.status)
        assertEquals(403, delete("/UserFavoriteItems/$trackId?userId=${b.id}", a.token).response.status)
        assertEquals(200, post("/UserFavoriteItems/$trackId?userId=${a.id}", emptyMap<String, Any>(), a.token).response.status)

        val reorderForOther = mapOf("UserId" to b.id, "ItemIds" to listOf(trackId))
        assertEquals(403, post("/Items/FavoriteOrder", reorderForOther, a.token).response.status)
        val reorderSelf = mapOf("UserId" to a.id, "ItemIds" to listOf(trackId))
        assertEquals(200, post("/Items/FavoriteOrder", reorderSelf, a.token).response.status)
    }

    @Test
    fun `adaptive sessions are bound to their owner`() {
        val a = seedUser("ses-a")
        val b = seedUser("ses-b")

        assertEquals(403, post("/v1/sessions", mapOf("userId" to b.id), a.token).response.status)

        val start = post("/v1/sessions", mapOf("userId" to a.id), a.token)
        assertEquals(200, start.response.status)
        val sessionId = objectMapper.readTree(start.response.contentAsString).get("id").asText()

        assertEquals(403, get("/v1/sessions/$sessionId/queue", b.token).response.status)
        assertEquals(
            403,
            mockMvc
                .perform(
                    MockMvcRequestBuilders
                        .patch("/v1/sessions/$sessionId/state")
                        .header("Authorization", "Bearer ${b.token}")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"attentionMode":"active"}"""),
                ).andReturn()
                .response.status,
        )
        assertEquals(
            403,
            post("/v1/sessions/$sessionId/signals", mapOf("track_id" to UUID.randomUUID().toString(), "signal_type" to "SKIP"), b.token)
                .response.status,
        )
        assertEquals(403, post("/v1/sessions/$sessionId/queue/refresh", emptyMap<String, Any>(), b.token).response.status)
        assertEquals(403, delete("/v1/sessions/$sessionId", b.token).response.status)

        assertEquals(200, get("/v1/sessions/$sessionId/queue", a.token).response.status)
        assertEquals(204, delete("/v1/sessions/$sessionId", a.token).response.status)
    }

    @Test
    fun `group operations require membership and destructive operations require ownership`() {
        val owner = seedUser("grp-owner")
        val outsider = seedUser("grp-outsider")

        val create = post("/v1/groups", mapOf("name" to "Boundary Party"), owner.token)
        assertEquals(200, create.response.status)
        val created = objectMapper.readTree(create.response.contentAsString)
        val groupId = created.get("id").asText()
        val joinCode = created.get("joinCode").asText()

        assertEquals(403, get("/v1/groups/$groupId", outsider.token).response.status)
        assertEquals(403, get("/v1/groups/$groupId/events", outsider.token).response.status)
        assertEquals(
            403,
            post("/v1/groups/$groupId/schedule", mapOf("expected_epoch" to 0, "action" to "PLAY"), outsider.token).response.status,
        )
        assertEquals(403, post("/v1/groups/$groupId/heartbeat", mapOf("rttMs" to 5), outsider.token).response.status)
        assertEquals(403, delete("/v1/groups/$groupId/members/${owner.deviceId}", outsider.token).response.status)
        assertEquals(403, delete("/v1/groups/$groupId", outsider.token).response.status)

        val join = post("/v1/groups/join", mapOf("joinCode" to joinCode), outsider.token)
        assertEquals(200, join.response.status)

        assertEquals(200, get("/v1/groups/$groupId", outsider.token).response.status)
        assertEquals(200, post("/v1/groups/$groupId/heartbeat", mapOf("rttMs" to 5), outsider.token).response.status)
        assertEquals(
            403,
            post("/v1/groups/$groupId/schedule", mapOf("expected_epoch" to 0, "action" to "PLAY"), outsider.token).response.status,
            "member but not owner must not control the schedule",
        )
        assertEquals(
            403,
            delete("/v1/groups/$groupId/members/${owner.deviceId}", outsider.token).response.status,
            "member must not kick another member's device",
        )
        assertEquals(403, delete("/v1/groups/$groupId", outsider.token).response.status, "member must not end the group")

        assertEquals(200, delete("/v1/groups/$groupId/members/${outsider.deviceId}", outsider.token).response.status)
        assertEquals(200, delete("/v1/groups/$groupId", owner.token).response.status)
        assertEquals(404, get("/v1/groups/$groupId", owner.token).response.status)
    }

    @Test
    fun `mcp ignores client-supplied user_id and binds to the authenticated principal`() {
        val a = seedUser("mcp-a")
        val b = seedUser("mcp-b")
        val playlistName = "secret-playlist-${UUID.randomUUID().toString().take(8)}"
        val uidB = UserId(b.id)
        playlistUseCases.execute(
            CreatePlaylist(PlaylistId(UUID.randomUUID().toString()), uidB, playlistName, null, false, Instant.now()),
            CommandContext(uidB, ProtocolId("JELLYFIN"), Instant.now(), IdempotencyKey(UUID.randomUUID().toString()), AggregateVersion.INITIAL),
        )

        fun listPlaylistsAs(token: String): String {
            val body =
                mapOf(
                    "jsonrpc" to "2.0",
                    "id" to 1,
                    "method" to "tools/call",
                    "params" to mapOf("name" to "list_playlists", "arguments" to mapOf("user_id" to b.id)),
                )
            val result = post("/mcp", body, token)
            assertEquals(200, result.response.status)
            return result.response.contentAsString
        }

        assertFalse(
            listPlaylistsAs(a.token).contains(playlistName),
            "user A must not see user B's playlists even when passing B's user_id",
        )
        assertTrue(listPlaylistsAs(b.token).contains(playlistName), "owner still sees own playlists")
    }

    @Test
    fun `mcp rejects unauthenticated requests`() {
        val body = mapOf("jsonrpc" to "2.0", "id" to 1, "method" to "tools/list")
        assertEquals(401, post("/mcp", body).response.status)
    }
}
