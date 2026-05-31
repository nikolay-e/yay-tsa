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
import org.springframework.http.HttpHeaders
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.UUID

class KaraokeRangeIntegrationTest : HttpIntegrationTestBase() {
    @Autowired
    lateinit var authUseCases: AuthUseCases

    @Autowired
    lateinit var jdbc: JdbcTemplate

    private lateinit var token: String
    private lateinit var trackId: UUID
    private val stemSize = 2048L

    // Stems live under the shared karaoke output-path (java.io.tmpdir, set in HttpIntegrationTestBase)
    // so MediaPathSafety resolves them; a dedicated @DynamicPropertySource would spawn a second
    // Spring context and collide on the singleton JCache CacheManager.
    private val karaokeDir: Path = Path.of(System.getProperty("java.io.tmpdir"))

    @BeforeEach
    fun seed() {
        val userId = UUID.randomUUID().toString()
        token = UUID.randomUUID().toString()
        val uid = UserId(userId)
        val now = Instant.now()
        authUseCases.execute(
            CreateUser(uid, "kar-${userId.take(8)}", "testpassword", "Test", null, false),
            CommandContext(uid, ProtocolId("JELLYFIN"), now, IdempotencyKey(UUID.randomUUID().toString()), AggregateVersion.INITIAL),
        )
        authUseCases.execute(
            CreateApiToken(uid, ApiTokenId(UUID.randomUUID().toString()), token, DeviceId("test"), "Test", null),
            CommandContext(uid, ProtocolId("JELLYFIN"), now, IdempotencyKey(UUID.randomUUID().toString()), AggregateVersion(1)),
        )

        trackId = UUID.randomUUID()
        val stem = karaokeDir.resolve("$trackId-instrumental.mp3")
        Files.write(stem, ByteArray(stemSize.toInt()) { it.toByte() })
        jdbc.update(
            "INSERT INTO core_v2_karaoke.assets (track_id, instrumental_path, ready_at) VALUES (?,?,?)",
            trackId,
            stem.toAbsolutePath().toString(),
            java.sql.Timestamp.from(Instant.now()),
        )
    }

    private fun range(value: String?) =
        mockMvc
            .perform(
                MockMvcRequestBuilders
                    .get("/Karaoke/$trackId/instrumental")
                    .header("Authorization", "Bearer $token")
                    .apply { if (value != null) header(HttpHeaders.RANGE, value) },
            ).andReturn()

    @Test
    fun `range past EOF is clamped to a 206, not rejected with 416 (RFC 7233)`() {
        val result = range("bytes=0-99999")
        assertEquals(206, result.response.status, "end past EOF must be clamped, not 416")
        assertEquals("bytes 0-${stemSize - 1}/$stemSize", result.response.getHeader(HttpHeaders.CONTENT_RANGE))
    }

    @Test
    fun `a normal sub-range returns 206 with the exact Content-Range`() {
        val result = range("bytes=0-1023")
        assertEquals(206, result.response.status)
        assertEquals("bytes 0-1023/$stemSize", result.response.getHeader(HttpHeaders.CONTENT_RANGE))
    }

    @Test
    fun `a suffix range returns the last bytes as 206`() {
        val result = range("bytes=-100")
        assertEquals(206, result.response.status)
        assertEquals("bytes ${stemSize - 100}-${stemSize - 1}/$stemSize", result.response.getHeader(HttpHeaders.CONTENT_RANGE))
    }

    @Test
    fun `a start past EOF is unsatisfiable and returns 416`() {
        val result = range("bytes=5000-")
        assertEquals(416, result.response.status, "first-byte-pos beyond the resource must be 416")
    }

    @Test
    fun `no range header serves the whole stem as 200`() {
        val result = range(null)
        assertEquals(200, result.response.status)
        assertEquals(stemSize.toString(), result.response.getHeader(HttpHeaders.CONTENT_LENGTH))
    }
}
