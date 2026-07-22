package dev.yaytsa.app.integration

import com.fasterxml.jackson.databind.JsonNode
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
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.util.UUID

class McpListeningToolsIntegrationTest : HttpIntegrationTestBase() {
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
            CreateUser(uid, "mcplisten-${userId.take(8)}", "testpassword", "Test", null, false),
            CommandContext(uid, ProtocolId("JELLYFIN"), now, IdempotencyKey(UUID.randomUUID().toString()), AggregateVersion.INITIAL),
        )
        authUseCases.execute(
            CreateApiToken(uid, ApiTokenId(UUID.randomUUID().toString()), token, DeviceId("mcplisten-dev"), "Test Device", null),
            CommandContext(uid, ProtocolId("JELLYFIN"), now, IdempotencyKey(UUID.randomUUID().toString()), AggregateVersion(1)),
        )
    }

    @Test
    fun `get_listening_stats renders provenance header wilson ci and low support flag`() {
        val artistName = "StatsArtist-${UUID.randomUUID().toString().take(6)}"
        val artistId = seedArtist(artistName)
        val trackId = seedTrack("StatsTrack", artistId, durationMs = 200_000L)
        val start = Instant.now().minus(Duration.ofHours(3))
        seedPlay(trackId, start, durationMs = 200_000L, playedMs = 200_000L, completed = true, skipped = false, source = "queue")
        seedPlay(trackId, start.plusSeconds(600), durationMs = 200_000L, playedMs = 15_000L, completed = false, skipped = true, source = "queue")
        seedPlay(trackId, start.plusSeconds(1200), durationMs = 0L, playedMs = 90_000L, completed = false, skipped = false, source = "queue")

        val result = mcpToolCall("get_listening_stats", mapOf("group_by" to "artist", "window_days" to 7))
        assertFalse(result.get("isError").asBoolean(), result.toString())
        val text = toolText(result)
        assertTrue(text.contains("source: play_history, 3 events"), text)
        assertTrue(text.contains("1 events missing duration (pre-fix rows)"), text)
        assertTrue(text.contains("$artistName: plays=3 skips=1"), text)
        assertTrue(text.contains("[CI "), text)
        assertTrue(text.contains("LOW SUPPORT (n<20)"), text)
    }

    @Test
    fun `get_listening_stats group_by track buckets per track with replay counts`() {
        val artistName = "ReplayArtist-${UUID.randomUUID().toString().take(6)}"
        val artistId = seedArtist(artistName)
        val replayedName = "ReplayedTrack-${UUID.randomUUID().toString().take(6)}"
        val replayed = seedTrack(replayedName, artistId, durationMs = 210_000L)
        val onceName = "OnceTrack-${UUID.randomUUID().toString().take(6)}"
        val once = seedTrack(onceName, artistId, durationMs = 190_000L)
        val start = Instant.now().minus(Duration.ofHours(6))
        repeat(3) { i ->
            seedPlay(replayed, start.plusSeconds(i * 3600L), 210_000L, 210_000L, completed = true, skipped = false, source = "queue")
        }
        seedPlay(once, start.plusSeconds(120), 190_000L, 190_000L, completed = true, skipped = false, source = "queue")

        val result = mcpToolCall("get_listening_stats", mapOf("group_by" to "track", "window_days" to 7))
        assertFalse(result.get("isError").asBoolean(), result.toString())
        val text = toolText(result)
        assertTrue(text.contains("$replayedName — $artistName"), text)
        assertTrue(text.contains("(id: $replayed)"), text)
        assertTrue(text.contains("plays=3"), text)
        assertTrue(text.contains("$onceName — $artistName"), text)
        assertTrue(text.indexOf(replayedName) < text.indexOf(onceName), "replayed track must rank above the single-play track: $text")
    }

    @Test
    fun `get_listening_history joins track and artist names with status and source`() {
        val artistName = "HistArtist-${UUID.randomUUID().toString().take(6)}"
        val artistId = seedArtist(artistName)
        val trackName = "HistTrack-${UUID.randomUUID().toString().take(6)}"
        val trackId = seedTrack(trackName, artistId, durationMs = 245_000L)
        val start = Instant.now().minus(Duration.ofHours(1))
        seedPlay(trackId, start, durationMs = 245_000L, playedMs = 245_000L, completed = true, skipped = false, source = "queue")
        seedPlay(trackId, start.plusSeconds(300), durationMs = 245_000L, playedMs = 20_000L, completed = false, skipped = true, source = "queue")

        val result = mcpToolCall("get_listening_history", mapOf("window_days" to 7, "limit" to 10))
        assertFalse(result.get("isError").asBoolean(), result.toString())
        val text = toolText(result)
        assertTrue(text.contains("source: play_history, 2 events"), text)
        assertTrue(text.contains(trackName), text)
        assertTrue(text.contains(artistName), text)
        assertTrue(text.contains("completed"), text)
        assertTrue(text.contains("skipped"), text)
        assertTrue(text.contains("source=queue"), text)
        assertTrue(text.contains("(id: $trackId)"), text)
    }

    @Test
    fun `get_track concise gives names and detailed adds provenance-labeled stats`() {
        val artistName = "TrackArtist-${UUID.randomUUID().toString().take(6)}"
        val artistId = seedArtist(artistName)
        val trackName = "LookupTrack-${UUID.randomUUID().toString().take(6)}"
        val trackId = seedTrack(trackName, artistId, durationMs = 185_000L)
        seedPlay(trackId, Instant.now().minusSeconds(900), 185_000L, 185_000L, completed = true, skipped = false, source = "queue")

        val concise = mcpToolCall("get_track", mapOf("track_id" to trackId))
        assertFalse(concise.get("isError").asBoolean(), concise.toString())
        val conciseText = toolText(concise)
        assertTrue(conciseText.contains(trackName), conciseText)
        assertTrue(conciseText.contains(artistName), conciseText)
        assertTrue(conciseText.contains("3:05"), conciseText)
        assertFalse(conciseText.contains("Affinity"), conciseText)

        val detailed = mcpToolCall("get_track", mapOf("track_id" to trackId, "response_format" to "detailed"))
        assertFalse(detailed.get("isError").asBoolean(), detailed.toString())
        val detailedText = toolText(detailed)
        assertTrue(detailedText.contains("Play stats (play_history"), detailedText)
        assertTrue(detailedText.contains("1 plays"), detailedText)
        assertTrue(detailedText.contains("Affinity"), detailedText)
    }

    @Test
    fun `create_playlist succeeds through mcp capabilities and same-name retry is a non-error guard`() {
        val trackId = seedTrack("PlTrack-${UUID.randomUUID().toString().take(6)}", null, 120_000L)
        val name = "Morning Focus ${UUID.randomUUID().toString().take(6)}"

        val created = mcpToolCall("create_playlist", mapOf("name" to name, "track_ids" to listOf(trackId)))
        // A failure here with "UnsupportedByProtocol" means the capability whitelist regressed
        assertFalse(created.get("isError").asBoolean(), created.toString())
        val createdText = toolText(created)
        assertTrue(createdText.contains("Created playlist '$name'"), createdText)
        val playlistId = extractId(createdText)

        val retry = mcpToolCall("create_playlist", mapOf("name" to name, "track_ids" to listOf(trackId)))
        assertFalse(retry.get("isError").asBoolean(), retry.toString())
        val retryText = toolText(retry)
        assertTrue(retryText.contains("already exists"), retryText)
        assertTrue(retryText.contains(playlistId), retryText)
        assertTrue(retryText.contains("update_playlist"), retryText)
    }

    @Test
    fun `create_playlist with unknown track ids is an honest error`() {
        val unknownId = UUID.randomUUID().toString()
        val result = mcpToolCall("create_playlist", mapOf("name" to "Bad ${UUID.randomUUID()}", "track_ids" to listOf(unknownId)))
        assertTrue(result.get("isError").asBoolean(), result.toString())
        assertTrue(toolText(result).contains(unknownId), toolText(result))
    }

    @Test
    fun `update_playlist refuses to empty a playlist without confirm_empty then obeys with it`() {
        val trackId = seedTrack("EmptyTrack-${UUID.randomUUID().toString().take(6)}", null, 120_000L)
        val name = "Emptying ${UUID.randomUUID().toString().take(6)}"
        val created = mcpToolCall("create_playlist", mapOf("name" to name, "track_ids" to listOf(trackId)))
        assertFalse(created.get("isError").asBoolean(), created.toString())
        val playlistId = extractId(toolText(created))

        val refused = mcpToolCall("update_playlist", mapOf("playlist_id" to playlistId, "remove_track_ids" to listOf(trackId)))
        assertTrue(refused.get("isError").asBoolean(), refused.toString())
        assertTrue(toolText(refused).contains("confirm_empty"), toolText(refused))

        val confirmed =
            mcpToolCall(
                "update_playlist",
                mapOf("playlist_id" to playlistId, "remove_track_ids" to listOf(trackId), "confirm_empty" to true),
            )
        assertFalse(confirmed.get("isError").asBoolean(), confirmed.toString())
        assertTrue(toolText(confirmed).contains("now 0 tracks"), toolText(confirmed))

        val fetched = mcpToolCall("get_playlist", mapOf("playlist_id" to playlistId))
        assertFalse(fetched.get("isError").asBoolean(), fetched.toString())
        assertTrue(toolText(fetched).contains("0 tracks"), toolText(fetched))
    }

    @Test
    fun `update_playlist reports adds skipped duplicates and rename in one call`() {
        val trackA = seedTrack("UpdA-${UUID.randomUUID().toString().take(6)}", null, 120_000L)
        val trackB = seedTrack("UpdB-${UUID.randomUUID().toString().take(6)}", null, 120_000L)
        val name = "Update Me ${UUID.randomUUID().toString().take(6)}"
        val created = mcpToolCall("create_playlist", mapOf("name" to name, "track_ids" to listOf(trackA)))
        assertFalse(created.get("isError").asBoolean(), created.toString())
        val playlistId = extractId(toolText(created))

        val newName = "$name v2"
        val updated =
            mcpToolCall(
                "update_playlist",
                mapOf("playlist_id" to playlistId, "add_track_ids" to listOf(trackA, trackB), "new_name" to newName),
            )
        assertFalse(updated.get("isError").asBoolean(), updated.toString())
        val text = toolText(updated)
        assertTrue(text.contains("renamed to '$newName'"), text)
        assertTrue(text.contains("added 1 (1 already present, skipped)"), text)
        assertTrue(text.contains("now 2 tracks"), text)
    }

    private fun extractId(text: String): String {
        val match = Regex("id: ([0-9a-fA-F-]{36})").find(text)
        assertTrue(match != null, "no playlist id in: $text")
        return match!!.groupValues[1]
    }

    private fun mcpToolCall(
        tool: String,
        args: Map<String, Any?> = emptyMap(),
    ): JsonNode {
        val body =
            mapOf(
                "jsonrpc" to "2.0",
                "id" to 1,
                "method" to "tools/call",
                "params" to mapOf("name" to tool, "arguments" to args),
            )
        val result = post("/mcp", body, token)
        assertEquals(200, result.response.status, result.response.contentAsString)
        return objectMapper.readTree(result.response.contentAsString).get("result")
    }

    private fun toolText(result: JsonNode): String =
        result
            .get("content")
            .get(0)
            .get("text")
            .asText()

    private fun seedArtist(name: String): String {
        val id = UUID.randomUUID()
        jdbc.update(
            "INSERT INTO core_v2_library.entities (id, entity_type, name, sort_name, search_text) VALUES (?,?,?,?,?)",
            id,
            "ARTIST",
            name,
            name.lowercase(),
            name.lowercase(),
        )
        return id.toString()
    }

    private fun seedTrack(
        name: String,
        artistId: String?,
        durationMs: Long,
    ): String {
        val id = UUID.randomUUID()
        jdbc.update(
            "INSERT INTO core_v2_library.entities (id, entity_type, name, sort_name, source_path, search_text) VALUES (?,?,?,?,?,?)",
            id,
            "TRACK",
            name,
            name.lowercase(),
            "/mcplisten/$id.flac",
            name.lowercase(),
        )
        jdbc.update(
            "INSERT INTO core_v2_library.audio_tracks (entity_id, duration_ms, album_artist_id) VALUES (?,?,?)",
            id,
            durationMs,
            artistId?.let { UUID.fromString(it) },
        )
        return id.toString()
    }

    private fun seedPlay(
        trackId: String,
        startedAt: Instant,
        durationMs: Long,
        playedMs: Long,
        completed: Boolean,
        skipped: Boolean,
        source: String?,
    ) {
        jdbc.update(
            "INSERT INTO core_v2_playback.play_history " +
                "(id, user_id, item_id, started_at, duration_ms, played_ms, completed, scrobbled, skipped, recorded_at, source, device_id) " +
                "VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
            UUID.randomUUID(),
            userId,
            trackId,
            Timestamp.from(startedAt),
            durationMs,
            playedMs,
            completed,
            false,
            skipped,
            Timestamp.from(startedAt),
            source,
            "mcplisten-dev",
        )
    }
}
