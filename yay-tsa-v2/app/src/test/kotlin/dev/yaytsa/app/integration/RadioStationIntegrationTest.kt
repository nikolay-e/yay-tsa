package dev.yaytsa.app.integration

import dev.yaytsa.application.adaptive.port.AdaptiveQueryPort
import dev.yaytsa.application.auth.AuthUseCases
import dev.yaytsa.domain.adaptive.ListeningSessionId
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
import java.time.Instant
import java.util.UUID

/**
 * End-to-end radio variety test against real pgvector + the real HTTP path.
 *
 * The geometry is synthetic (we cannot ship real MERT weights), but it is *realistic*: a tight
 * same-album blob is the seed's literal nearest-neighbour set (shared dim-0 → cosine ≈ 1), and the
 * rest of the catalogue spreads across distinct albums farther out. That is exactly the situation
 * that produced the user's album-lock — and it lets us assert the de-clustering *contract* (the
 * blob must NOT dominate the station) rather than re-asserting the fixture's own shape. The pre-fix
 * bootstrap (`findSimilarTracks(seed, 20).take(10)`) returns ~10 blob tracks and fails the album
 * assertion below; the diversified builder passes it.
 */
class RadioStationIntegrationTest : HttpIntegrationTestBase() {
    @Autowired
    lateinit var authUseCases: AuthUseCases

    @Autowired
    lateinit var jdbc: org.springframework.jdbc.core.JdbcTemplate

    @Autowired
    lateinit var adaptiveQuery: AdaptiveQueryPort

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

    private fun vec(vararg nonZero: Pair<Int, Float>): FloatArray = FloatArray(MERT_DIM) { 0f }.also { v -> nonZero.forEach { (i, x) -> v[i] = x } }

    /** Inserts a TRACK with its album/artist ids and (optionally) a MERT embedding. */
    private fun seedTrack(
        albumId: UUID?,
        artistId: UUID?,
        mert: FloatArray?,
    ): UUID {
        val id = UUID.randomUUID()
        jdbc.update(
            "INSERT INTO core_v2_library.entities (id, entity_type, name, sort_name, source_path) VALUES (?,?,?,?,?)",
            id,
            "TRACK",
            "Track $id",
            "track $id",
            "Radio/$id.flac",
        )
        jdbc.update(
            "INSERT INTO core_v2_library.audio_tracks (entity_id, album_id, album_artist_id, duration_ms) VALUES (?,?,?,?)",
            id,
            albumId,
            artistId,
            180000L,
        )
        if (mert != null) {
            // Explicit CAST so the insert works regardless of the datasource's stringtype setting
            // (the app test datasource binds String params as varchar, which won't implicitly coerce
            // to pgvector's `vector` type the way the JPA converter relies on elsewhere).
            val literal = mert.joinToString(prefix = "[", postfix = "]", separator = ",")
            jdbc.update(
                "INSERT INTO core_v2_ml.track_features (track_id, embedding_mert, extracted_at, extractor_version) VALUES (?, CAST(? AS vector(768)), ?, ?)",
                id,
                literal,
                java.sql.Timestamp.from(Instant.now()),
                "test",
            )
        }
        return id
    }

    private fun startRadio(
        seedTrackId: UUID,
        token: String,
    ): String {
        val res = post("/v1/sessions", mapOf("seed_track_id" to seedTrackId.toString()), token)
        assertEquals(200, res.response.status)
        return objectMapper.readTree(res.response.contentAsString).get("id").asText()
    }

    @Test
    fun `radio from an album track does not lock to that album`() {
        val user = seedUser("radio")
        val albumA = UUID.randomUUID()
        val artistA = UUID.randomUUID()

        // The seed + a tight 12-track same-album blob: all share dim 0 (cosine ≈ 1 to the seed), so
        // they are the literal nearest neighbours — the album-lock trap.
        val seed = seedTrack(albumA, artistA, vec(0 to 1f))
        repeat(12) { k -> seedTrack(albumA, artistA, vec(0 to 1f, 100 + k to 0.02f)) }

        // 45 tracks across distinct albums/artists, farther out (lower dim-0 weight) but still related.
        repeat(45) { i -> seedTrack(UUID.randomUUID(), UUID.randomUUID(), vec(0 to 0.55f, 200 + i to 0.83f)) }

        val sessionId = startRadio(seed, user.token)
        assertEquals(204, post("/v1/sessions/$sessionId/queue/refresh", emptyMap<String, Any>(), user.token).response.status)

        val entries = adaptiveQuery.getQueueEntries(ListeningSessionId(sessionId))
        val trackToAlbum = albumByTrack()
        val albumACount = entries.count { trackToAlbum[UUID.fromString(it.trackId.value)] == albumA }
        val distinctAlbums = entries.mapNotNull { trackToAlbum[UUID.fromString(it.trackId.value)] }.toSet().size

        assertTrue(entries.size >= 20, "radio station should be populated, was ${entries.size}")
        // The blob must not dominate: seed + at most one capped album-A track. Pre-fix this is ~10.
        assertTrue(albumACount <= 2, "seed album must not dominate the station, had $albumACount tracks")
        // Real variety: the station spans many distinct albums, not one.
        assertTrue(distinctAlbums >= 15, "station should span many albums, had $distinctAlbums")
    }

    @Test
    fun `radio extends endlessly without the LLM`() {
        val user = seedUser("endless")
        val seed = seedTrack(UUID.randomUUID(), UUID.randomUUID(), vec(0 to 1f))
        repeat(60) { i -> seedTrack(UUID.randomUUID(), UUID.randomUUID(), vec(0 to 0.6f, 300 + i to 0.8f)) }

        val sessionId = startRadio(seed, user.token)
        post("/v1/sessions/$sessionId/queue/refresh", emptyMap<String, Any>(), user.token)
        val afterBootstrap = adaptiveQuery.getQueueEntries(ListeningSessionId(sessionId)).size

        // A second refresh on a non-empty queue must APPEND a fresh tail (the client near-end trigger),
        // proving the station is endless even with yaytsa.llm.enabled=false (the test default).
        assertEquals(204, post("/v1/sessions/$sessionId/queue/refresh", emptyMap<String, Any>(), user.token).response.status)
        val afterExtend = adaptiveQuery.getQueueEntries(ListeningSessionId(sessionId))

        assertTrue(afterExtend.size > afterBootstrap, "queue should grow on near-end refresh ($afterBootstrap -> ${afterExtend.size})")
        assertTrue(afterExtend.any { it.intentLabel == "ml-extend" }, "appended tail should be labelled ml-extend")
    }

    @Test
    fun `cold seed without embeddings is reported as degraded, not silently random`() {
        val user = seedUser("cold")
        repeat(10) { seedTrack(UUID.randomUUID(), UUID.randomUUID(), vec(0 to 1f)) } // library has analyzed tracks...
        val coldSeed = seedTrack(UUID.randomUUID(), UUID.randomUUID(), null) // ...but the seed itself has no embedding

        val res = post("/v1/sessions", mapOf("seed_track_id" to coldSeed.toString()), user.token)
        assertEquals(200, res.response.status)
        val body = objectMapper.readTree(res.response.contentAsString)
        assertTrue(body.get("isRadioMode").asBoolean(), "seeded session is radio mode")
        assertEquals("no_embedding", body.get("degraded").asText(), "cold seed must be honestly flagged")
    }

    @Test
    fun `well-analyzed seed is not flagged degraded`() {
        val user = seedUser("warm")
        val seed = seedTrack(UUID.randomUUID(), UUID.randomUUID(), vec(0 to 1f))
        repeat(20) { i -> seedTrack(UUID.randomUUID(), UUID.randomUUID(), vec(0 to 0.7f, 400 + i to 0.7f)) }

        val res = post("/v1/sessions", mapOf("seed_track_id" to seed.toString()), user.token)
        assertEquals(200, res.response.status)
        // A non-degraded radio reports degraded=null; the app ObjectMapper omits null fields, so the
        // key is simply absent. Either way it must not carry a degradation reason.
        val degraded = objectMapper.readTree(res.response.contentAsString).get("degraded")
        assertTrue(degraded == null || degraded.isNull, "a seed with a rich neighbourhood must not be flagged degraded")
    }

    private fun albumByTrack(): Map<UUID, UUID?> =
        jdbc
            .query("SELECT entity_id, album_id FROM core_v2_library.audio_tracks") { rs, _ ->
                rs.getObject("entity_id", UUID::class.java) to rs.getObject("album_id", UUID::class.java)
            }.toMap()

    private companion object {
        const val MERT_DIM = 768
    }
}
