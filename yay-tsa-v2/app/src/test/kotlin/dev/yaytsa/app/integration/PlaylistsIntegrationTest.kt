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
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import java.time.Instant
import java.util.UUID

class PlaylistsIntegrationTest : HttpIntegrationTestBase() {
    @Autowired
    lateinit var authUseCases: AuthUseCases

    @Autowired
    lateinit var jdbc: JdbcTemplate

    private lateinit var token: String
    private lateinit var userId: String

    @BeforeEach
    fun seedUser() {
        userId = UUID.randomUUID().toString()
        token = UUID.randomUUID().toString()
        val uid = UserId(userId)
        val now = Instant.now()
        authUseCases.execute(
            CreateUser(uid, "pl-${userId.take(8)}", "testpassword", "Test", null, false),
            CommandContext(uid, ProtocolId("JELLYFIN"), now, IdempotencyKey(UUID.randomUUID().toString()), AggregateVersion.INITIAL),
        )
        authUseCases.execute(
            CreateApiToken(uid, ApiTokenId(UUID.randomUUID().toString()), token, DeviceId("test"), "Test", null),
            CommandContext(uid, ProtocolId("JELLYFIN"), now, IdempotencyKey(UUID.randomUUID().toString()), AggregateVersion(1)),
        )
    }

    @Test
    fun `created playlist resolves via GET Items by id as Type Playlist, not 404`() {
        val create = post("/Playlists", mapOf("Name" to "My Mix", "UserId" to userId), token)
        assertEquals(200, create.response.status)
        val playlistId = objectMapper.readTree(create.response.contentAsString).get("Id").asText()

        val item = get("/Items/$playlistId", token)
        assertEquals(200, item.response.status, "GET /Items/{playlistId} must resolve, not 404")
        val body = objectMapper.readTree(item.response.contentAsString)
        assertEquals("Playlist", body.get("Type").asText())
        assertEquals(playlistId, body.get("Id").asText())
    }

    private fun seedTrack(): String {
        val id = UUID.randomUUID()
        jdbc.update(
            "INSERT INTO core_v2_library.entities (id, entity_type, name, sort_name, source_path, search_text) VALUES (?,?,?,?,?,?)",
            id,
            "TRACK",
            "DupTrack-${id.toString().take(6)}",
            "duptrack",
            "/duptest/$id.flac",
            "duptrack",
        )
        jdbc.update("INSERT INTO core_v2_library.audio_tracks (entity_id, duration_ms) VALUES (?,?)", id, 100000L)
        return id.toString()
    }

    @Test
    fun `removing one slot of a duplicated track keeps the other slot`() {
        val trackId = seedTrack()
        val create = post("/Playlists", mapOf("Name" to "Dup Mix", "UserId" to userId), token)
        assertEquals(200, create.response.status)
        val playlistId = objectMapper.readTree(create.response.contentAsString).get("Id").asText()

        assertEquals(204, post("/Playlists/$playlistId/Items?Ids=$trackId", emptyMap<String, Any>(), token).response.status)
        assertEquals(204, post("/Playlists/$playlistId/Items?Ids=$trackId", emptyMap<String, Any>(), token).response.status)

        val beforeItems = objectMapper.readTree(get("/Playlists/$playlistId/Items", token).response.contentAsString)
        assertEquals(2, beforeItems.get("Items").size(), "playlist must hold the same track twice")
        val firstSlotId = beforeItems.get("Items")[0].get("PlaylistItemId").asText()
        assertEquals("0", firstSlotId, "PlaylistItemId must be the absolute slot position")

        val remove = delete("/Playlists/$playlistId/Items?EntryIds=0", token)
        assertEquals(204, remove.response.status)

        val afterItems = objectMapper.readTree(get("/Playlists/$playlistId/Items", token).response.contentAsString)
        assertEquals(1, afterItems.get("Items").size(), "removing one slot must not delete both copies")
        assertEquals(trackId, afterItems.get("Items")[0].get("Id").asText(), "the surviving slot must still reference the same track")
    }

    @Test
    fun `non-integer EntryIds is rejected with 400`() {
        val create = post("/Playlists", mapOf("Name" to "Bad Remove", "UserId" to userId), token)
        val playlistId = objectMapper.readTree(create.response.contentAsString).get("Id").asText()
        val remove = delete("/Playlists/$playlistId/Items?EntryIds=not-a-number", token)
        assertEquals(400, remove.response.status)
    }

    @Test
    fun `playlist appears in the owner playlist listing`() {
        val name = "Listing ${UUID.randomUUID().toString().take(6)}"
        post("/Playlists", mapOf("Name" to name, "UserId" to userId), token)
        val list = get("/Items?IncludeItemTypes=Playlist", token)
        assertEquals(200, list.response.status)
        assertTrue(list.response.contentAsString.contains(name), "created playlist must appear in the Playlist listing")
    }
}
