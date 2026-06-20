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
import org.springframework.security.crypto.bcrypt.BCrypt
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.UUID

// Asserts the materialized-cover contract end to end: a track-level Primary image row (what the
// scanner writes for an audiobook with embedded art) makes /Items/{trackId}/Images/Primary serve 200
// from the cover cache AND makes /v1/me/audiobooks advertise ImageTags.Primary — advertise == serve,
// no asymmetry.
class AudiobookCoverIntegrationTest : HttpIntegrationTestBase() {
    @Autowired
    lateinit var authUseCases: AuthUseCases

    @Autowired
    lateinit var jdbc: JdbcTemplate

    private lateinit var token: String

    @BeforeEach
    fun seedUser() {
        val userId = UUID.randomUUID().toString()
        token = UUID.randomUUID().toString()
        val uid = UserId(userId)
        val now = Instant.now()
        authUseCases.execute(
            CreateUser(uid, "ab-${userId.take(8)}", BCrypt.hashpw("testpassword", BCrypt.gensalt(4)), "Test", null, false),
            CommandContext(uid, ProtocolId("JELLYFIN"), now, IdempotencyKey(UUID.randomUUID().toString()), AggregateVersion.INITIAL),
        )
        authUseCases.execute(
            CreateApiToken(uid, ApiTokenId(UUID.randomUUID().toString()), token, DeviceId("test"), "Test", null),
            CommandContext(uid, ProtocolId("JELLYFIN"), now, IdempotencyKey(UUID.randomUUID().toString()), AggregateVersion(1)),
        )
    }

    private fun cachedCover(): Path {
        val cover = Path.of(coverCacheDir).resolve("${UUID.randomUUID()}.jpg")
        Files.write(cover, byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xD9.toByte()))
        return cover
    }

    @Test
    fun `audiobook track with a track-level cover serves 200 and advertises ImageTags`() {
        val trackId = UUID.randomUUID()
        jdbc.update(
            "INSERT INTO core_v2_library.entities (id, entity_type, name, sort_name, source_path) VALUES (?,?,?,?,?)",
            trackId,
            "TRACK",
            "Kashchey Chapter One",
            "kashchey chapter one",
            "Sektor Gaza/Kashchey/01.flac",
        )
        jdbc.update("INSERT INTO core_v2_library.audio_tracks (entity_id, codec, duration_ms) VALUES (?,?,?)", trackId, "flac", 600000L)
        val genreId = UUID.randomUUID()
        jdbc.update("INSERT INTO core_v2_library.genres (id, name) VALUES (?, ?) ON CONFLICT (name) DO NOTHING", genreId, "Audiobook")
        val resolvedGenreId = jdbc.queryForObject("SELECT id FROM core_v2_library.genres WHERE name = ?", UUID::class.java, "Audiobook")
        jdbc.update("INSERT INTO core_v2_library.entity_genres (entity_id, genre_id) VALUES (?, ?)", trackId, resolvedGenreId)
        jdbc.update(
            "INSERT INTO core_v2_library.images (id, entity_id, image_type, path, is_primary) VALUES (?,?,?,?,true)",
            UUID.randomUUID(),
            trackId,
            "Primary",
            cachedCover().toAbsolutePath().toString(),
        )

        val image = get("/Items/$trackId/Images/Primary", token)
        assertEquals(200, image.response.status, "a track-level cover row must serve 200, not 404")
        assertEquals("image/jpeg", image.response.contentType)

        val audiobooks = get("/v1/me/audiobooks", token)
        assertEquals(200, audiobooks.response.status)
        val tree = objectMapper.readTree(audiobooks.response.contentAsString)
        val mine = tree.firstOrNull { it.path("item").path("Id").asText() == trackId.toString() }
        assertTrue(mine != null, "the seeded audiobook track must be listed")
        assertEquals(
            trackId.toString(),
            mine!!
                .path("item")
                .path("ImageTags")
                .path("Primary")
                .asText(),
            "the audiobook item must advertise ImageTags.Primary == its own id",
        )
    }
}
