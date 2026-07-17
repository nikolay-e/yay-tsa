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
    fun `create playlist with null element in Ids returns 400 problem json not 500`() {
        val result =
            mockMvc
                .perform(
                    org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/Playlists")
                        .header("Authorization", "Bearer $token")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("""{"Name":"Null Ids","UserId":"$userId","Ids":["a",null]}"""),
                ).andReturn()
        assertEquals(400, result.response.status)
        assertTrue(
            result.response.contentType!!.startsWith("application/problem+json"),
            "expected problem+json, was ${result.response.contentType}",
        )
    }

    @Test
    fun `a 300-char name is rejected 400 by the domain, never reaching the varchar(255) column`() {
        val create = post("/Playlists", mapOf("Name" to "n".repeat(300), "UserId" to userId), token)
        assertEquals(400, create.response.status)
        assertTrue(
            create.response.contentType!!.startsWith("application/problem+json"),
            "expected problem+json, was ${create.response.contentType}",
        )
        assertTrue(create.response.contentAsString.contains("must not exceed 255"))
    }

    @Test
    fun `a NUL byte in a name is stripped at the edge, never sent to the DB`() {
        // A NUL passes the domain blank-check but Postgres rejects it (invalid UTF8 0x00), raising a
        // DataIntegrityViolationException whose raw-SQL message Hibernate logs at ERROR before any
        // handler runs. The Jackson edge strip removes the NUL so the create succeeds with a sanitized
        // name instead of tripping the DB. Build the NUL via 0.toChar(); never as a source string
        // escape, which some editors write as a real NUL byte.
        val nulName = "qa${0.toChar()}name"
        val create = post("/Playlists", mapOf("Name" to nulName, "UserId" to userId), token)
        assertEquals(200, create.response.status, "a NUL must be scrubbed and the create accepted, not 400/500")
        val playlistId = objectMapper.readTree(create.response.contentAsString).get("Id").asText()
        val item = objectMapper.readTree(get("/Items/$playlistId", token).response.contentAsString)
        assertEquals("qaname", item.get("Name").asText(), "the NUL is stripped, the surrounding text preserved")
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
    fun `move by PlaylistItemId reorders the playlist`() {
        val trackA = seedTrack()
        val trackB = seedTrack()
        val create = post("/Playlists", mapOf("Name" to "Reorder Mix", "UserId" to userId), token)
        val playlistId = objectMapper.readTree(create.response.contentAsString).get("Id").asText()
        assertEquals(204, post("/Playlists/$playlistId/Items?Ids=$trackA,$trackB", emptyMap<String, Any>(), token).response.status)

        val before = objectMapper.readTree(get("/Playlists/$playlistId/Items", token).response.contentAsString)
        val firstEntryId = before.get("Items")[0].get("PlaylistItemId").asText()
        val firstTrackId = before.get("Items")[0].get("Id").asText()

        val move = post("/Playlists/$playlistId/Items/$firstEntryId/Move/1", emptyMap<String, Any>(), token)
        assertEquals(204, move.response.status, "Move by the wire PlaylistItemId must succeed, not 404")

        val after = objectMapper.readTree(get("/Playlists/$playlistId/Items", token).response.contentAsString)
        assertEquals(firstTrackId, after.get("Items")[1].get("Id").asText(), "moved entry must occupy the target slot")
    }

    @Test
    fun `move by track id is still accepted`() {
        val trackA = seedTrack()
        val trackB = seedTrack()
        val create = post("/Playlists", mapOf("Name" to "Reorder Compat", "UserId" to userId), token)
        val playlistId = objectMapper.readTree(create.response.contentAsString).get("Id").asText()
        assertEquals(204, post("/Playlists/$playlistId/Items?Ids=$trackA,$trackB", emptyMap<String, Any>(), token).response.status)

        val move = post("/Playlists/$playlistId/Items/$trackA/Move/1", emptyMap<String, Any>(), token)
        assertEquals(204, move.response.status)

        val after = objectMapper.readTree(get("/Playlists/$playlistId/Items", token).response.contentAsString)
        assertEquals(trackA, after.get("Items")[1].get("Id").asText())
    }

    @Test
    fun `rename via POST Playlists id updates the name and is visible on read`() {
        val create = post("/Playlists", mapOf("Name" to "Old Name", "UserId" to userId), token)
        assertEquals(200, create.response.status)
        val playlistId = objectMapper.readTree(create.response.contentAsString).get("Id").asText()

        val rename = post("/Playlists/$playlistId", mapOf("Name" to "New Name"), token)
        assertEquals(204, rename.response.status)

        val item = objectMapper.readTree(get("/Items/$playlistId", token).response.contentAsString)
        assertEquals("New Name", item.get("Name").asText())
    }

    @Test
    fun `rename accepts camelCase name alias`() {
        val create = post("/Playlists", mapOf("Name" to "Alias Mix", "UserId" to userId), token)
        val playlistId = objectMapper.readTree(create.response.contentAsString).get("Id").asText()

        val rename = post("/Playlists/$playlistId", mapOf("name" to "Aliased Name"), token)
        assertEquals(204, rename.response.status)

        val item = objectMapper.readTree(get("/Items/$playlistId", token).response.contentAsString)
        assertEquals("Aliased Name", item.get("Name").asText())
    }

    @Test
    fun `rename with blank name is rejected 400 and the old name survives`() {
        val create = post("/Playlists", mapOf("Name" to "Keep Me", "UserId" to userId), token)
        val playlistId = objectMapper.readTree(create.response.contentAsString).get("Id").asText()

        assertEquals(400, post("/Playlists/$playlistId", mapOf("Name" to "   "), token).response.status)
        assertEquals(400, post("/Playlists/$playlistId", emptyMap<String, Any>(), token).response.status)

        val item = objectMapper.readTree(get("/Items/$playlistId", token).response.contentAsString)
        assertEquals("Keep Me", item.get("Name").asText())
    }

    @Test
    fun `rename of a missing playlist returns 404 and a malformed id 400`() {
        assertEquals(404, post("/Playlists/${UUID.randomUUID()}", mapOf("Name" to "Ghost"), token).response.status)
        assertEquals(400, post("/Playlists/not-a-uuid", mapOf("Name" to "Ghost"), token).response.status)
    }

    @Test
    fun `a different user cannot rename someone else's playlist`() {
        val otherToken = seedSecondUser()
        val create = post("/Playlists", mapOf("Name" to "Owner Only", "UserId" to userId), token)
        val playlistId = objectMapper.readTree(create.response.contentAsString).get("Id").asText()

        val rename = post("/Playlists/$playlistId", mapOf("Name" to "Hijacked"), otherToken)
        assertEquals(401, rename.response.status)

        val item = objectMapper.readTree(get("/Items/$playlistId", token).response.contentAsString)
        assertEquals("Owner Only", item.get("Name").asText())
    }

    @Test
    fun `playlist appears in the owner playlist listing`() {
        val name = "Listing ${UUID.randomUUID().toString().take(6)}"
        post("/Playlists", mapOf("Name" to name, "UserId" to userId), token)
        val list = get("/Items?IncludeItemTypes=Playlist", token)
        assertEquals(200, list.response.status)
        assertTrue(list.response.contentAsString.contains(name), "created playlist must appear in the Playlist listing")
    }

    private fun seedSecondUser(): String {
        val otherUserId = UUID.randomUUID().toString()
        val otherToken = UUID.randomUUID().toString()
        val uid = UserId(otherUserId)
        val now = Instant.now()
        authUseCases.execute(
            CreateUser(uid, "pl2-${otherUserId.take(8)}", "testpassword", "Test2", null, false),
            CommandContext(uid, ProtocolId("JELLYFIN"), now, IdempotencyKey(UUID.randomUUID().toString()), AggregateVersion.INITIAL),
        )
        authUseCases.execute(
            CreateApiToken(uid, ApiTokenId(UUID.randomUUID().toString()), otherToken, DeviceId("test2"), "Test2", null),
            CommandContext(uid, ProtocolId("JELLYFIN"), now, IdempotencyKey(UUID.randomUUID().toString()), AggregateVersion(1)),
        )
        return otherToken
    }

    // Regression (BOLA / OWASP API1:2023): getPlaylistItems is a query, so it never went
    // through PlaylistHandler's `snapshot.owner != ctx.userId` check that every mutating
    // command gets — any authenticated user could read any other user's private playlist
    // tracks just by knowing the playlist UUID. Confirmed live in production before the fix.
    @Test
    fun `a private playlist's tracks are not readable by a different authenticated user`() {
        val otherToken = seedSecondUser()
        val create = post("/Playlists", mapOf("Name" to "Private Mix", "UserId" to userId, "IsPublic" to false), token)
        assertEquals(200, create.response.status)
        val playlistId = objectMapper.readTree(create.response.contentAsString).get("Id").asText()

        val ownerRead = get("/Playlists/$playlistId/Items", token)
        assertEquals(200, ownerRead.response.status, "owner must still be able to read their own private playlist")

        val strangerRead = get("/Playlists/$playlistId/Items", otherToken)
        assertEquals(404, strangerRead.response.status, "a different user must not see a private playlist's tracks")
    }

    @Test
    fun `a public playlist's tracks ARE readable by a different authenticated user`() {
        val otherToken = seedSecondUser()
        val create = post("/Playlists", mapOf("Name" to "Public Mix", "UserId" to userId, "IsPublic" to true), token)
        assertEquals(200, create.response.status)
        val playlistId = objectMapper.readTree(create.response.contentAsString).get("Id").asText()

        val strangerRead = get("/Playlists/$playlistId/Items", otherToken)
        assertEquals(200, strangerRead.response.status, "a public playlist must remain readable by other users")
    }

    // Regression (BOLA / OWASP API1:2023): the /Items/{id} playlist fallback branch in
    // JellyfinItemsController.getItem returned name/childCount for ANY playlist id with no
    // owner/isPublic check — metadata-only leak (no track list), but still a genuine
    // unauthorized disclosure of a private resource's existence and name.
    @Test
    fun `GET Items by id does not leak a private playlist's metadata to a different user`() {
        val otherToken = seedSecondUser()
        val create = post("/Playlists", mapOf("Name" to "Secret Metadata Mix", "UserId" to userId, "IsPublic" to false), token)
        assertEquals(200, create.response.status)
        val playlistId = objectMapper.readTree(create.response.contentAsString).get("Id").asText()

        val ownerRead = get("/Items/$playlistId", token)
        assertEquals(200, ownerRead.response.status, "owner must still resolve their own private playlist")

        val strangerRead = get("/Items/$playlistId", otherToken)
        assertEquals(404, strangerRead.response.status, "a different user must not learn a private playlist's name/metadata")
    }
}
