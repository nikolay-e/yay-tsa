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
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import java.time.Instant
import java.util.UUID

class FavoritesConsistencyIntegrationTest : HttpIntegrationTestBase() {
    @Autowired
    lateinit var authUseCases: AuthUseCases

    @Autowired
    lateinit var jdbc: JdbcTemplate

    private lateinit var token: String
    private val trackIds = mutableListOf<UUID>()

    @BeforeEach
    fun seed() {
        val userId = UUID.randomUUID().toString()
        token = UUID.randomUUID().toString()
        val uid = UserId(userId)
        val now = Instant.now()
        authUseCases.execute(
            CreateUser(uid, "fav-${userId.take(8)}", "testpassword", "Test", null, false),
            CommandContext(uid, ProtocolId("JELLYFIN"), now, IdempotencyKey(UUID.randomUUID().toString()), AggregateVersion.INITIAL),
        )
        authUseCases.execute(
            CreateApiToken(uid, ApiTokenId(UUID.randomUUID().toString()), token, DeviceId("test"), "Test", null),
            CommandContext(uid, ProtocolId("JELLYFIN"), now, IdempotencyKey(UUID.randomUUID().toString()), AggregateVersion(1)),
        )

        trackIds.clear()
        repeat(3) { idx ->
            val id = UUID.randomUUID()
            jdbc.update(
                "INSERT INTO core_v2_library.entities (id, entity_type, name, sort_name, source_path) VALUES (?,?,?,?,?)",
                id,
                "TRACK",
                "Fav Track $idx",
                "fav track $idx",
                "Fav/Album/$idx.flac",
            )
            jdbc.update("INSERT INTO core_v2_library.audio_tracks (entity_id, duration_ms) VALUES (?,?)", id, 120000L)
            trackIds.add(id)
            assertEquals(200, post("/UserFavoriteItems/$id", emptyMap<String, Any>(), token).response.status)
        }
    }

    @Test
    fun `favorites TotalRecordCount counts only resolvable tracks after one vanishes`() {
        val before = objectMapper.readTree(get("/Items?IsFavorite=true&Limit=50", token).response.contentAsString)
        assertEquals(3, before.get("TotalRecordCount").asInt())
        assertEquals(3, before.get("Items").size())

        // Simulate a vanished track (deleted/renamed file swept by the scanner) while the
        // favorite entry persists in the preferences aggregate.
        val gone = trackIds.first()
        jdbc.update("DELETE FROM core_v2_library.audio_tracks WHERE entity_id = ?", gone)
        jdbc.update("DELETE FROM core_v2_library.entities WHERE id = ?", gone)

        val after = objectMapper.readTree(get("/Items?IsFavorite=true&Limit=50", token).response.contentAsString)
        assertEquals(2, after.get("TotalRecordCount").asInt(), "count must drop the vanished favorite so infinite scroll terminates")
        assertEquals(2, after.get("Items").size(), "items and count must agree")
    }

    @Test
    fun `re-favoriting an already-favorite track is idempotent, not a 409 conflict`() {
        val id = trackIds.first()
        // The track is already a favorite (seeded in @BeforeEach). A second POST is a handler no-op
        // (returns the aggregate unchanged, no version bump), so the use case must skip the OCC write
        // rather than run UPDATE ... WHERE version = newVersion - 1 — which matched zero rows on a
        // no-op and surfaced as a 409 on every idempotent re-favorite / re-star.
        assertEquals(200, post("/UserFavoriteItems/$id", emptyMap<String, Any>(), token).response.status)
        assertEquals(200, post("/UserFavoriteItems/$id", emptyMap<String, Any>(), token).response.status)

        val body = objectMapper.readTree(get("/Items?IsFavorite=true&Limit=50", token).response.contentAsString)
        assertEquals(3, body.get("TotalRecordCount").asInt(), "re-favoriting must neither duplicate nor drop the favorite")
    }
}
