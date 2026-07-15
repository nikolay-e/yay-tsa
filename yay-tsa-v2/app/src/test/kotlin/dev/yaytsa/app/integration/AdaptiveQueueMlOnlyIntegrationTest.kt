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
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import java.time.Instant
import java.util.UUID

class AdaptiveQueueMlOnlyIntegrationTest : HttpIntegrationTestBase() {
    @Autowired
    lateinit var authUseCases: AuthUseCases

    @Autowired
    lateinit var jdbc: JdbcTemplate

    private data class Seeded(
        val id: String,
        val token: String,
    )

    private fun seedUser(prefix: String): Seeded {
        val id = UUID.randomUUID().toString()
        val token = UUID.randomUUID().toString()
        val uid = UserId(id)
        val now = Instant.now()
        authUseCases.execute(
            CreateUser(uid, "$prefix-${id.take(8)}", "testpassword", "Test", null, false),
            CommandContext(uid, ProtocolId("JELLYFIN"), now, IdempotencyKey(UUID.randomUUID().toString()), AggregateVersion.INITIAL),
        )
        authUseCases.execute(
            CreateApiToken(uid, ApiTokenId(UUID.randomUUID().toString()), token, DeviceId("dev-$prefix"), "Test", null),
            CommandContext(uid, ProtocolId("JELLYFIN"), now, IdempotencyKey(UUID.randomUUID().toString()), AggregateVersion(1)),
        )
        return Seeded(id, token)
    }

    private fun vec(vararg nonZero: Pair<Int, Float>): FloatArray {
        val v = FloatArray(MERT_DIM) { 0f }
        nonZero.forEach { (i, x) -> v[i] = x }
        return v
    }

    private fun seedTrack(mert: FloatArray?): UUID {
        val id = UUID.randomUUID()
        jdbc.update(
            "INSERT INTO core_v2_library.entities (id, entity_type, name, sort_name, source_path) VALUES (?,?,?,?,?)",
            id,
            "TRACK",
            "AdaptiveMl $id",
            "adaptiveml $id",
            "AdaptiveMl/$id.flac",
        )
        jdbc.update(
            "INSERT INTO core_v2_library.audio_tracks (entity_id, album_id, album_artist_id, duration_ms) VALUES (?,?,?,?)",
            id,
            UUID.randomUUID(),
            UUID.randomUUID(),
            180000L,
        )
        if (mert != null) {
            val literal = mert.joinToString(prefix = "[", postfix = "]", separator = ",")
            jdbc.update(
                "INSERT INTO core_v2_ml.track_features (track_id, embedding_mert, extracted_at, extractor_version) " +
                    "VALUES (?, CAST(? AS vector(768)), ?, ?)",
                id,
                literal,
                java.sql.Timestamp.from(Instant.now()),
                "test",
            )
        }
        return id
    }

    private fun createSession(
        token: String,
        seedTrackId: UUID?,
    ): JsonNode {
        val body = if (seedTrackId == null) emptyMap() else mapOf("seed_track_id" to seedTrackId.toString())
        val res = post("/v1/sessions", body, token)
        assertEquals(200, res.response.status, res.response.contentAsString)
        return objectMapper.readTree(res.response.contentAsString)
    }

    private fun refresh(
        sessionId: String,
        token: String,
    ) = assertEquals(204, post("/v1/sessions/$sessionId/queue/refresh", emptyMap<String, Any>(), token).response.status)

    private fun queueTracks(
        sessionId: String,
        token: String,
    ): List<JsonNode> {
        val res = get("/v1/sessions/$sessionId/queue", token)
        assertEquals(200, res.response.status, res.response.contentAsString)
        return objectMapper.readTree(res.response.contentAsString).get("tracks").toList()
    }

    @Test
    fun `adaptive session bootstraps an ml-seeded queue over http with llm disabled`() {
        val user = seedUser("mlboot")
        val seed = seedTrack(vec(ANCHOR_A to 1f))
        repeat(30) { i -> seedTrack(vec(ANCHOR_A to 0.6f, ANCHOR_A + 2 + i to 0.8f)) }

        val session = createSession(user.token, seed)
        assertTrue(session.get("isRadioMode").asBoolean(), "seeded session must be radio mode")
        val degraded = session.get("degraded")
        assertTrue(degraded == null || degraded.isNull, "rich ml neighbourhood must not be degraded: $session")

        val sessionId = session.get("id").asText()
        refresh(sessionId, user.token)

        val tracks = queueTracks(sessionId, user.token)
        assertTrue(tracks.size >= 10, "ml-only bootstrap must fill the station, had ${tracks.size}")
        assertEquals(seed.toString(), tracks.first().get("trackId").asText(), "seed track must lead the station")
        assertEquals("seed-track", tracks.first().get("addedReason").asText())
        assertTrue(tracks.all { it.get("intentLabel").asText() == "radio" }, "bootstrap entries must be labelled radio")
        assertTrue(tracks.all { it.get("queueVersion").asLong() == 1L }, "bootstrap rewrite must stamp queue_version 1")
    }

    @Test
    fun `queue refill increments queue_version and appends an ml-extend tail`() {
        val user = seedUser("mlext")
        val seed = seedTrack(vec(ANCHOR_B to 1f))
        repeat(60) { i -> seedTrack(vec(ANCHOR_B to 0.6f, ANCHOR_B + 2 + i to 0.8f)) }

        val sessionId = createSession(user.token, seed).get("id").asText()
        refresh(sessionId, user.token)
        val afterBootstrap = queueTracks(sessionId, user.token)

        refresh(sessionId, user.token)
        val afterExtend = queueTracks(sessionId, user.token)

        assertTrue(afterExtend.size > afterBootstrap.size, "refill must append (${afterBootstrap.size} -> ${afterExtend.size})")
        val tail = afterExtend.drop(afterBootstrap.size)
        assertTrue(tail.isNotEmpty() && tail.all { it.get("intentLabel").asText() == "ml-extend" }, "appended tail must be ml-extend")
        assertTrue(tail.all { it.get("queueVersion").asLong() == 2L }, "second rewrite must increment queue_version to 2")
        assertTrue(
            afterExtend.take(afterBootstrap.size).all { it.get("queueVersion").asLong() == 1L },
            "kept head must retain its original queue_version",
        )
        val extendedTrackIds = afterExtend.map { it.get("trackId").asText() }
        assertEquals(extendedTrackIds.size, extendedTrackIds.toSet().size, "refill must not enqueue duplicates")
    }

    @Test
    fun `session without a seed stays empty on refresh instead of guessing`() {
        val user = seedUser("mlnoseed")
        seedTrack(vec(ANCHOR_C to 1f))

        val session = createSession(user.token, null)
        assertEquals(false, session.get("isRadioMode").asBoolean())

        val sessionId = session.get("id").asText()
        refresh(sessionId, user.token)
        assertTrue(queueTracks(sessionId, user.token).isEmpty(), "non-radio session must not be auto-seeded")
    }

    private companion object {
        const val MERT_DIM = 768
        const val ANCHOR_A = 520
        const val ANCHOR_B = 560
        const val ANCHOR_C = 630
    }
}
