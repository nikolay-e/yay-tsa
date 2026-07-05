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
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders
import java.time.Instant
import java.util.UUID

class ResumeHttpIntegrationTest : HttpIntegrationTestBase() {
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
            CreateUser(uid, "resume-${userId.take(8)}", "testpassword", "Test", null, false),
            CommandContext(uid, ProtocolId("JELLYFIN"), now, IdempotencyKey(UUID.randomUUID().toString()), AggregateVersion.INITIAL),
        )
        authUseCases.execute(
            CreateApiToken(uid, ApiTokenId(UUID.randomUUID().toString()), token, DeviceId("test"), "Test", null),
            CommandContext(uid, ProtocolId("JELLYFIN"), now, IdempotencyKey(UUID.randomUUID().toString()), AggregateVersion(1)),
        )
    }

    private fun seedTrack(
        durationMs: Long = 100_000,
        audiobook: Boolean = false,
        trackNumber: Int? = null,
    ): String {
        val id = UUID.randomUUID().toString()
        jdbc.update(
            "INSERT INTO core_v2_library.entities (id, entity_type, name, sort_name, source_path, search_text) VALUES (?::uuid,?,?,?,?,?)",
            id,
            "TRACK",
            "Book ${id.take(6)}",
            "Book ${id.take(6)}",
            "/tmp/$id.m4b",
            "book",
        )
        jdbc.update(
            "INSERT INTO core_v2_library.audio_tracks (entity_id, duration_ms, track_number) VALUES (?::uuid,?,?)",
            id,
            durationMs,
            trackNumber,
        )
        if (audiobook) {
            jdbc.update(
                "INSERT INTO core_v2_library.genres (id, name) VALUES (?::uuid, 'Audiobook') ON CONFLICT (name) DO NOTHING",
                UUID.randomUUID().toString(),
            )
            val genreId = jdbc.queryForObject("SELECT id FROM core_v2_library.genres WHERE name = 'Audiobook'", String::class.java)
            jdbc.update("INSERT INTO core_v2_library.entity_genres (entity_id, genre_id) VALUES (?::uuid, ?::uuid)", id, genreId)
        }
        return id
    }

    private fun reportProgress(
        itemId: String,
        positionTicks: Long,
        isPaused: Boolean = false,
        eventName: String? = null,
        eventTime: Long? = null,
    ) {
        val fields =
            buildList {
                add("\"ItemId\":\"$itemId\"")
                add("\"PositionTicks\":$positionTicks")
                add("\"IsPaused\":$isPaused")
                eventName?.let { add("\"EventName\":\"$it\"") }
                eventTime?.let { add("\"EventTime\":$it") }
            }.joinToString(",")
        val status =
            mockMvc
                .perform(
                    MockMvcRequestBuilders
                        .post("/Sessions/Playing/Progress")
                        .header("X-Emby-Token", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{$fields}"),
                ).andReturn()
                .response.status
        assertEquals(204, status)
    }

    private fun getJson(url: String) =
        objectMapper.readTree(
            mockMvc
                .perform(MockMvcRequestBuilders.get(url).header("X-Emby-Token", token))
                .andReturn()
                .response.contentAsString,
        )

    private fun postJson(url: String) =
        objectMapper.readTree(
            mockMvc
                .perform(MockMvcRequestBuilders.post(url).header("X-Emby-Token", token))
                .andReturn()
                .response.contentAsString,
        )

    @Test
    fun `Items hydrates PlaybackPositionTicks from resume position`() {
        val trackId = seedTrack()
        reportProgress(trackId, positionTicks = 300_000_000) // 30s

        val item = getJson("/Items/$trackId")
        assertEquals(300_000_000, item.get("UserData").get("PlaybackPositionTicks").asLong())
    }

    @Test
    fun `audiobooks tab lists in-progress audiobook`() {
        val trackId = seedTrack(audiobook = true)
        reportProgress(trackId, positionTicks = 300_000_000)

        val list = getJson("/v1/me/audiobooks")
        assertTrue(list.isArray && list.size() >= 1)
        val entry = list.first { it.get("item").get("Id").asText() == trackId }
        assertEquals("in_progress", entry.get("resume").get("status").asText())
        assertEquals(30_000, entry.get("resume").get("positionMs").asLong())
    }

    @Test
    fun `audiobooks tab lists never-played audiobook as not_started`() {
        val trackId = seedTrack(audiobook = true)

        val list = getJson("/v1/me/audiobooks")
        val entry = list.first { it.get("item").get("Id").asText() == trackId }
        assertEquals("not_started", entry.get("resume").get("status").asText())
        assertEquals(0, entry.get("resume").get("positionMs").asLong())
        assertEquals(100_000, entry.get("resume").get("runTimeMs").asLong())
    }

    @Test
    fun `audiobook chapter exposes IndexNumber for canonical ordering`() {
        val trackId = seedTrack(audiobook = true, trackNumber = 7)

        val entry = getJson("/v1/me/audiobooks").first { it.get("item").get("Id").asText() == trackId }
        assertEquals(7, entry.get("item").get("IndexNumber").asInt())
    }

    @Test
    fun `non-audiobook track is excluded from audiobooks tab`() {
        val trackId = seedTrack(audiobook = false)
        reportProgress(trackId, positionTicks = 300_000_000)

        val list = getJson("/v1/me/audiobooks")
        assertTrue(list.none { it.get("item").get("Id").asText() == trackId })
    }

    @Test
    fun `backward seek is honored as exact-set and not clamped forward`() {
        val trackId = seedTrack(audiobook = true)
        reportProgress(trackId, positionTicks = 600_000_000) // heartbeat to 60s (furthest-wins)
        reportProgress(trackId, positionTicks = 100_000_000, eventName = "Seek") // rewind to 10s

        val entry = getJson("/v1/me/audiobooks").first { it.get("item").get("Id").asText() == trackId }
        assertEquals(10_000, entry.get("resume").get("positionMs").asLong())
    }

    @Test
    fun `forward heartbeat after seek still advances`() {
        val trackId = seedTrack(audiobook = true)
        reportProgress(trackId, positionTicks = 100_000_000, eventName = "Seek") // seek to 10s
        reportProgress(trackId, positionTicks = 200_000_000) // play forward to 20s

        val entry = getJson("/v1/me/audiobooks").first { it.get("item").get("Id").asText() == trackId }
        assertEquals(20_000, entry.get("resume").get("positionMs").asLong())
    }

    @Test
    fun `late stale beacon with older event time does not clobber newer position`() {
        val trackId = seedTrack(audiobook = true)
        val now = Instant.now().toEpochMilli()
        // A newer device advances to 50s.
        reportProgress(trackId, positionTicks = 500_000_000, eventName = "Seek", eventTime = now)
        // A delayed beacon from a backgrounded tab carries an OLDER event time and a stale position.
        reportProgress(trackId, positionTicks = 50_000_000, eventName = "Seek", eventTime = now - 60_000)

        val entry = getJson("/v1/me/audiobooks").first { it.get("item").get("Id").asText() == trackId }
        assertEquals(50_000, entry.get("resume").get("positionMs").asLong())
    }

    @Test
    fun `mark finished on a never-started chapter creates a finished row instead of 404`() {
        val trackId = seedTrack(audiobook = true)

        assertEquals("finished", postJson("/v1/me/audiobooks/$trackId/finished").get("status").asText())

        val entry = getJson("/v1/me/audiobooks").first { it.get("item").get("Id").asText() == trackId }
        assertEquals("finished", entry.get("resume").get("status").asText())
        assertTrue(
            entry
                .get("item")
                .get("UserData")
                .get("Played")
                .asBoolean(),
        )
    }

    @Test
    fun `mark finished then restart flips status to relistening and resets position`() {
        val trackId = seedTrack(audiobook = true)
        reportProgress(trackId, positionTicks = 300_000_000)

        assertEquals("finished", postJson("/v1/me/audiobooks/$trackId/finished").get("status").asText())

        val restarted = postJson("/v1/me/audiobooks/$trackId/restart")
        assertEquals("relistening", restarted.get("status").asText())
        assertEquals(0, restarted.get("positionMs").asLong())
    }
}
