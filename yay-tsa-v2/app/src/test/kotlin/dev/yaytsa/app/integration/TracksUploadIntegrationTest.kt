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
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.mock.web.MockMultipartFile
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.util.UUID

class TracksUploadIntegrationTest : HttpIntegrationTestBase() {
    @Autowired
    lateinit var authUseCases: AuthUseCases

    private val musicRoot: Path = Path.of(System.getProperty("java.io.tmpdir"))

    private data class Seeded(
        val id: String,
        val token: String,
    )

    private fun seedUser(
        prefix: String,
        isAdmin: Boolean = false,
    ): Seeded {
        val id = UUID.randomUUID().toString()
        val token = UUID.randomUUID().toString()
        val uid = UserId(id)
        val now = Instant.now()
        authUseCases.execute(
            CreateUser(uid, "$prefix-${id.take(8)}", "testpassword", "Test", null, isAdmin),
            CommandContext(uid, ProtocolId("JELLYFIN"), now, IdempotencyKey(UUID.randomUUID().toString()), AggregateVersion.INITIAL),
        )
        authUseCases.execute(
            CreateApiToken(uid, ApiTokenId(UUID.randomUUID().toString()), token, DeviceId("test"), "Test", null),
            CommandContext(uid, ProtocolId("JELLYFIN"), now, IdempotencyKey(UUID.randomUUID().toString()), AggregateVersion(1)),
        )
        return Seeded(id, token)
    }

    private fun fixtureBytes(): ByteArray =
        requireNotNull(javaClass.getResourceAsStream("/fixtures/silent-3s.flac")) { "fixture silent-3s.flac missing" }
            .use { it.readAllBytes() }

    private fun taggedFlacBytes(
        artist: String,
        album: String,
        title: String,
    ): ByteArray {
        val temp = Files.createTempFile("upload-fixture-", ".flac")
        javaClass.getResourceAsStream("/fixtures/silent-3s.flac").use { input ->
            Files.copy(requireNotNull(input) { "fixture silent-3s.flac missing" }, temp, StandardCopyOption.REPLACE_EXISTING)
        }
        val audioFile = AudioFileIO.read(temp.toFile())
        val tag = audioFile.tagOrCreateAndSetDefault
        tag.setField(FieldKey.ARTIST, artist)
        tag.setField(FieldKey.ALBUM, album)
        tag.setField(FieldKey.TITLE, title)
        AudioFileIO.write(audioFile)
        val bytes = Files.readAllBytes(temp)
        Files.deleteIfExists(temp)
        return bytes
    }

    private fun upload(
        token: String?,
        filename: String,
        bytes: ByteArray,
    ): MvcResult {
        val builder =
            MockMvcRequestBuilders
                .multipart("/tracks/upload")
                .file(MockMultipartFile("file", filename, "audio/flac", bytes))
        if (token != null) {
            builder.header("Authorization", "Bearer $token")
        }
        return mockMvc.perform(builder).andReturn()
    }

    @Test
    fun `admin upload lands under the tag-derived artist-album folder and becomes queryable`() {
        val admin = seedUser("upload-admin", isAdmin = true)
        val suffix = UUID.randomUUID().toString().take(8)
        val artist = "Upload Artist $suffix"
        val album = "Upload Album"
        val filename = "uploaded-track-$suffix.flac"
        val bytes = taggedFlacBytes(artist, album, "Uploaded Track $suffix")

        val result = upload(admin.token, filename, bytes)

        assertEquals(200, result.response.status, "upload must succeed: ${result.response.contentAsString}")
        val body = objectMapper.readTree(result.response.contentAsString)
        val itemId = body.get("Id").asText()
        assertEquals("$artist/$album/$filename", body.get("Path").asText(), "file must land under Artist/Album")
        assertEquals(false, body.get("Duplicate").asBoolean())
        assertTrue(Files.isRegularFile(musicRoot.resolve(artist).resolve(album).resolve(filename)), "file must exist in the library root")
        assertEquals(200, get("/Items/$itemId", admin.token).response.status, "ingested item must be queryable right after upload")

        val replay = upload(admin.token, filename, bytes)
        assertEquals(200, replay.response.status)
        val replayBody = objectMapper.readTree(replay.response.contentAsString)
        assertEquals(itemId, replayBody.get("Id").asText(), "identical re-upload must dedupe to the same item")
        assertEquals(true, replayBody.get("Duplicate").asBoolean())
    }

    @Test
    fun `same filename with different content is stored under a suffixed name`() {
        val admin = seedUser("upload-admin", isAdmin = true)
        val suffix = UUID.randomUUID().toString().take(8)
        val artist = "Conflict Artist $suffix"
        val album = "Conflict Album"
        val filename = "conflict-$suffix.flac"

        val first = upload(admin.token, filename, taggedFlacBytes(artist, album, "First $suffix"))
        assertEquals(200, first.response.status)
        val second = upload(admin.token, filename, taggedFlacBytes(artist, album, "Second $suffix"))
        assertEquals(200, second.response.status)

        val firstBody = objectMapper.readTree(first.response.contentAsString)
        val secondBody = objectMapper.readTree(second.response.contentAsString)
        assertEquals("$artist/$album/conflict-$suffix (1).flac", secondBody.get("Path").asText(), "conflicting upload must get a suffix")
        assertTrue(firstBody.get("Id").asText() != secondBody.get("Id").asText(), "conflicting upload must create a distinct item")
        assertTrue(Files.isRegularFile(musicRoot.resolve(artist).resolve(album).resolve(filename)), "original file must survive untouched")
    }

    @Test
    fun `untagged upload lands in the Uploads folder`() {
        val admin = seedUser("upload-admin", isAdmin = true)
        val suffix = UUID.randomUUID().toString().take(8)
        val filename = "untagged-$suffix.flac"

        val result = upload(admin.token, filename, fixtureBytes())

        assertEquals(200, result.response.status, "upload must succeed: ${result.response.contentAsString}")
        val body = objectMapper.readTree(result.response.contentAsString)
        assertEquals("Uploads/$filename", body.get("Path").asText(), "untagged file must land in Uploads/")
        assertTrue(Files.isRegularFile(musicRoot.resolve("Uploads").resolve(filename)))
    }

    @Test
    fun `upload requires an admin caller`() {
        val user = seedUser("upload-user")
        assertEquals(403, upload(user.token, "denied.flac", fixtureBytes()).response.status)
        assertEquals(401, upload(null, "denied.flac", fixtureBytes()).response.status)
    }

    @Test
    fun `filenames with path segments are rejected`() {
        val admin = seedUser("upload-admin", isAdmin = true)
        assertEquals(400, upload(admin.token, "../evil.flac", fixtureBytes()).response.status)
        assertEquals(400, upload(admin.token, "nested/evil.flac", fixtureBytes()).response.status)
        assertEquals(400, upload(admin.token, ".hidden.flac", fixtureBytes()).response.status)
    }

    @Test
    fun `a multipart body missing the file part is a 400 not a 500`() {
        val admin = seedUser("upload-admin", isAdmin = true)
        val result =
            mockMvc
                .perform(
                    MockMvcRequestBuilders
                        .multipart("/tracks/upload")
                        .file(MockMultipartFile("wrongname", "x.flac", "audio/flac", fixtureBytes()))
                        .header("Authorization", "Bearer ${admin.token}"),
                ).andReturn()
        assertEquals(400, result.response.status, result.response.contentAsString)
    }

    @Test
    fun `disallowed extensions are rejected with 415`() {
        val admin = seedUser("upload-admin", isAdmin = true)
        assertEquals(415, upload(admin.token, "malware.exe", fixtureBytes()).response.status)
        assertEquals(415, upload(admin.token, "noextension", fixtureBytes()).response.status)
    }

    @Test
    fun `uploads over the configured size cap are rejected with 413`() {
        val admin = seedUser("upload-admin", isAdmin = true)
        assertEquals(413, upload(admin.token, "huge.flac", ByteArray(300_000)).response.status)
    }

    @Test
    fun `replaygain refresh is admin-only and reports started or already-running`() {
        val user = seedUser("rg-user")
        val admin = seedUser("rg-admin", isAdmin = true)

        assertEquals(403, post("/Admin/Library/RefreshReplayGain", emptyMap<String, Any>(), user.token).response.status)

        val result = post("/Admin/Library/RefreshReplayGain", emptyMap<String, Any>(), admin.token)
        assertTrue(result.response.status in setOf(202, 409), "unexpected status ${result.response.status}")
        val status = objectMapper.readTree(result.response.contentAsString).get("status").asText()
        assertTrue(status in setOf("started", "already_running"))
    }
}
