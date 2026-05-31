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

class ArtistPaginationIntegrationTest : HttpIntegrationTestBase() {
    @Autowired
    lateinit var authUseCases: AuthUseCases

    @Autowired
    lateinit var jdbc: JdbcTemplate

    private lateinit var token: String
    private lateinit var artistId: UUID

    @BeforeEach
    fun seed() {
        val userId = UUID.randomUUID().toString()
        token = UUID.randomUUID().toString()
        val uid = UserId(userId)
        val now = Instant.now()
        authUseCases.execute(
            CreateUser(uid, "artist-${userId.take(8)}", "testpassword", "Test", null, false),
            CommandContext(uid, ProtocolId("JELLYFIN"), now, IdempotencyKey(UUID.randomUUID().toString()), AggregateVersion.INITIAL),
        )
        authUseCases.execute(
            CreateApiToken(uid, ApiTokenId(UUID.randomUUID().toString()), token, DeviceId("test"), "Test", null),
            CommandContext(uid, ProtocolId("JELLYFIN"), now, IdempotencyKey(UUID.randomUUID().toString()), AggregateVersion(1)),
        )

        artistId = UUID.randomUUID()
        insertEntity(artistId, "ARTIST", "Paginated Artist", "paginated artist")
        jdbc.update("INSERT INTO core_v2_library.artists (entity_id) VALUES (?)", artistId)

        // Two albums, three tracks each = 6 tracks owned by this artist.
        repeat(2) { albumIdx ->
            val albumId = UUID.randomUUID()
            insertEntity(albumId, "ALBUM", "Album $albumIdx", "album $albumIdx")
            jdbc.update("INSERT INTO core_v2_library.albums (entity_id, artist_id) VALUES (?,?)", albumId, artistId)
            repeat(3) { trackIdx ->
                val trackId = UUID.randomUUID()
                insertEntity(trackId, "TRACK", "Track $albumIdx-$trackIdx", "track $albumIdx $trackIdx")
                jdbc.update(
                    "INSERT INTO core_v2_library.audio_tracks (entity_id, album_id, album_artist_id, track_number, disc_number, duration_ms) " +
                        "VALUES (?,?,?,?,?,?)",
                    trackId,
                    albumId,
                    artistId,
                    trackIdx + 1,
                    1,
                    120000L,
                )
            }
        }
    }

    private fun insertEntity(
        id: UUID,
        type: String,
        name: String,
        sort: String,
    ) {
        jdbc.update(
            "INSERT INTO core_v2_library.entities (id, entity_type, name, sort_name, source_path, search_text) VALUES (?,?,?,?,?,?)",
            id,
            type,
            name,
            sort,
            "$type/${id.toString().take(8)}",
            sort,
        )
    }

    @Test
    fun `recursive artist browse returns the full track count regardless of page`() {
        val result = get("/Items?ParentId=$artistId&Recursive=true&Limit=2&StartIndex=2", token)
        assertEquals(200, result.response.status)
        val body = objectMapper.readTree(result.response.contentAsString)
        assertEquals(2, body.get("Items").size(), "page must hold exactly Limit items")
        assertEquals(6, body.get("TotalRecordCount").asInt(), "total must be all 6 tracks, not the page size")
    }

    @Test
    fun `recursive artist pagination yields every track once with no duplicates across pages`() {
        val seen = mutableListOf<String>()
        var start = 0
        while (true) {
            val body = objectMapper.readTree(get("/Items?ParentId=$artistId&Recursive=true&Limit=4&StartIndex=$start", token).response.contentAsString)
            val items = body.get("Items")
            if (items.size() == 0) break
            items.forEach { seen.add(it.get("Id").asText()) }
            start += items.size()
            if (start >= 6) break
        }
        assertEquals(6, seen.size, "all 6 tracks paged through")
        assertEquals(6, seen.toSet().size, "no duplicates or skips across the offset boundary")
    }

    @Test
    fun `offset past the end returns an empty page with the correct total`() {
        val body = objectMapper.readTree(get("/Items?ParentId=$artistId&Recursive=true&Limit=5&StartIndex=100", token).response.contentAsString)
        assertEquals(0, body.get("Items").size())
        assertTrue(body.get("TotalRecordCount").asInt() == 6, "total stable even past the end")
    }
}
