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
import java.time.Instant
import java.util.UUID

class AudioStreamIntegrationTest : HttpIntegrationTestBase() {
    @Autowired
    lateinit var authUseCases: AuthUseCases

    @Autowired
    lateinit var jdbc: JdbcTemplate

    private lateinit var token: String
    private lateinit var username: String

    @BeforeEach
    fun seedUser() {
        val userId = UUID.randomUUID().toString()
        token = UUID.randomUUID().toString()
        username = "stream-${userId.take(8)}"
        val uid = UserId(userId)
        val now = Instant.now()
        authUseCases.execute(
            CreateUser(uid, username, "testpassword", "Test", null, false),
            CommandContext(uid, ProtocolId("JELLYFIN"), now, IdempotencyKey(UUID.randomUUID().toString()), AggregateVersion.INITIAL),
        )
        authUseCases.execute(
            CreateApiToken(uid, ApiTokenId(UUID.randomUUID().toString()), token, DeviceId("test"), "Test", null),
            CommandContext(uid, ProtocolId("JELLYFIN"), now, IdempotencyKey(UUID.randomUUID().toString()), AggregateVersion(1)),
        )
    }

    @Test
    fun `ALAC stream returns 206 with audio mp4 content type on a Range request`() {
        val file = Files.createTempFile("yaytsa-alac-", ".alac")
        Files.write(file, ByteArray(2048) { it.toByte() })
        val trackId = UUID.randomUUID()
        jdbc.update(
            "INSERT INTO core_v2_library.entities (id, entity_type, name, sort_name, source_path) VALUES (?,?,?,?,?)",
            trackId,
            "TRACK",
            "Alac Track",
            "alac track",
            file.toAbsolutePath().toString(),
        )
        jdbc.update(
            "INSERT INTO core_v2_library.audio_tracks (entity_id, codec, duration_ms) VALUES (?,?,?)",
            trackId,
            "alac",
            120000L,
        )

        val result =
            mockMvc
                .perform(
                    MockMvcRequestBuilders
                        .get("/Audio/$trackId/stream")
                        .header("Authorization", "Bearer $token")
                        .header(HttpHeaders.RANGE, "bytes=0-1023"),
                ).andReturn()

        assertEquals(206, result.response.status, "Range request must return 206 Partial Content")
        assertEquals("audio/mp4", result.response.contentType, "ALAC must map to audio/mp4, not audio/mpeg")
        Files.deleteIfExists(file)
    }

    private fun seedTrack(
        codec: String,
        ext: String,
    ): UUID {
        val file = Files.createTempFile("yaytsa-$codec-", ".$ext")
        Files.write(file, ByteArray(2048) { it.toByte() })
        val trackId = UUID.randomUUID()
        jdbc.update(
            "INSERT INTO core_v2_library.entities (id, entity_type, name, sort_name, source_path) VALUES (?,?,?,?,?)",
            trackId,
            "TRACK",
            "$codec track",
            "$codec track",
            file.toAbsolutePath().toString(),
        )
        jdbc.update("INSERT INTO core_v2_library.audio_tracks (entity_id, codec, duration_ms) VALUES (?,?,?)", trackId, codec, 120000L)
        return trackId
    }

    @Test
    fun `WMA maps to audio x-ms-wma, not audio mpeg`() {
        val trackId = seedTrack("wma", "wma")
        val result =
            mockMvc
                .perform(
                    MockMvcRequestBuilders.get("/Audio/$trackId/stream").header("Authorization", "Bearer $token").header(HttpHeaders.RANGE, "bytes=0-1023"),
                ).andReturn()
        assertEquals(206, result.response.status)
        assertEquals("audio/x-ms-wma", result.response.contentType)
    }

    @Test
    fun `Subsonic stream returns 416 on an unsatisfiable Range, not a corrupt 206`() {
        val trackId = seedTrack("flac", "flac")
        val result =
            mockMvc
                .perform(
                    MockMvcRequestBuilders
                        .get("/rest/stream")
                        .param("id", trackId.toString())
                        .param("u", username)
                        .param("p", "testpassword")
                        .param("v", "1.16.1")
                        .param("c", "test")
                        .header(HttpHeaders.RANGE, "bytes=99999999-"),
                ).andReturn()
        assertEquals(416, result.response.status, "past-EOF range must be 416, not a 206 with negative content-length")
    }

    @Test
    fun `Subsonic stream clamps an over-long Range end to a valid 206`() {
        val trackId = seedTrack("flac", "flac")
        val result =
            mockMvc
                .perform(
                    MockMvcRequestBuilders
                        .get("/rest/stream")
                        .param("id", trackId.toString())
                        .param("u", username)
                        .param("p", "testpassword")
                        .param("v", "1.16.1")
                        .param("c", "test")
                        .header(HttpHeaders.RANGE, "bytes=0-99999999"),
                ).andReturn()
        assertEquals(206, result.response.status, "end past EOF must clamp to a 206, not 416")
        assertEquals("bytes 0-2047/2048", result.response.getHeader(HttpHeaders.CONTENT_RANGE))
    }

    @Test
    fun `Subsonic getCoverArt resolves a cover id to the image bytes`() {
        val albumId = UUID.randomUUID()
        jdbc.update(
            "INSERT INTO core_v2_library.entities (id, entity_type, name, sort_name, source_path) VALUES (?,?,?,?,?)",
            albumId,
            "ALBUM",
            "Cover Album",
            "cover album",
            "Cover/Album",
        )
        jdbc.update("INSERT INTO core_v2_library.albums (entity_id) VALUES (?)", albumId)
        val cover = Files.createTempFile("yaytsa-cover-", ".jpg")
        // Minimal JPEG SOI/EOI marker bytes.
        Files.write(cover, byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xD9.toByte()))
        jdbc.update(
            "INSERT INTO core_v2_library.images (id, entity_id, image_type, path, is_primary) VALUES (?,?,?,?,true)",
            UUID.randomUUID(),
            albumId,
            "Primary",
            cover.toAbsolutePath().toString(),
        )

        val result =
            mockMvc
                .perform(
                    MockMvcRequestBuilders
                        .get("/rest/getCoverArt")
                        .param("id", albumId.toString())
                        .param("u", username)
                        .param("p", "testpassword")
                        .param("v", "1.16.1")
                        .param("c", "test"),
                ).andReturn()
        assertEquals(200, result.response.status, "a valid cover id must serve the image, not 404")
        assertEquals("image/jpeg", result.response.contentType)
        Files.deleteIfExists(cover)
    }
}
