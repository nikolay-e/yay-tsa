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

// Regression for a bug where the "Audio" branch's personalized (SortBy=DatePlayed) page 0
// reported TotalRecordCount as the FULL library count, so infinite-scroll clients requested a
// page 1 that falls back to a deterministic browse query — one with no "DatePlayed" column to
// continue from, so it silently reproduced/skipped tracks already shown on the personalized
// page 0. The fix reports TotalRecordCount as the personalized page's own size, matching the
// MusicAlbum/MusicArtist branches, so clients correctly stop after the one bounded page.
class PersonalizedTracksPaginationIntegrationTest : HttpIntegrationTestBase() {
    @Autowired
    lateinit var authUseCases: AuthUseCases

    @Autowired
    lateinit var jdbc: JdbcTemplate

    private lateinit var token: String
    private lateinit var seededTrackIds: List<UUID>

    private val trackLimit = 3
    private val totalTracksInLibrary = 10

    @BeforeEach
    fun seed() {
        val userId = UUID.randomUUID().toString()
        token = UUID.randomUUID().toString()
        val uid = UserId(userId)
        val now = Instant.now()
        authUseCases.execute(
            CreateUser(uid, "personalized-${userId.take(8)}", "testpassword", "Test", null, false),
            CommandContext(uid, ProtocolId("JELLYFIN"), now, IdempotencyKey(UUID.randomUUID().toString()), AggregateVersion.INITIAL),
        )
        authUseCases.execute(
            CreateApiToken(uid, ApiTokenId(UUID.randomUUID().toString()), token, DeviceId("test"), "Test", null),
            CommandContext(uid, ProtocolId("JELLYFIN"), now, IdempotencyKey(UUID.randomUUID().toString()), AggregateVersion(1)),
        )

        val artistId = UUID.randomUUID()
        insertEntity(artistId, "ARTIST", "Personalized Artist", "personalized artist")
        jdbc.update("INSERT INTO core_v2_library.artists (entity_id) VALUES (?)", artistId)
        val albumId = UUID.randomUUID()
        insertEntity(albumId, "ALBUM", "Personalized Album", "personalized album")
        jdbc.update("INSERT INTO core_v2_library.albums (entity_id, artist_id) VALUES (?,?)", albumId, artistId)

        // More tracks than trackLimit — proves the total isn't silently the full library count.
        seededTrackIds =
            (0 until totalTracksInLibrary).map { idx ->
                val trackId = UUID.randomUUID()
                insertEntity(trackId, "TRACK", "Track $idx", "track $idx")
                jdbc.update(
                    "INSERT INTO core_v2_library.audio_tracks (entity_id, album_id, album_artist_id, track_number, disc_number, duration_ms) " +
                        "VALUES (?,?,?,?,?,?)",
                    trackId,
                    albumId,
                    artistId,
                    idx + 1,
                    1,
                    120000L,
                )
                trackId
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
    fun `personalized page 0 reports its own size as the total, not the full library count`() {
        val result = get("/Items?IncludeItemTypes=Audio&SortBy=DatePlayed&StartIndex=0&Limit=$trackLimit", token)
        assertEquals(200, result.response.status)
        val body = objectMapper.readTree(result.response.contentAsString)
        assertEquals(trackLimit, body.get("Items").size(), "page must hold exactly Limit items")
        assertEquals(
            trackLimit,
            body.get("TotalRecordCount").asInt(),
            "total must equal the personalized page size, never the full $totalTracksInLibrary-track library",
        )
    }

    @Test
    fun `a client that trusts TotalRecordCount never requests a page 1 for a personalized surface`() {
        val body = objectMapper.readTree(get("/Items?IncludeItemTypes=Audio&SortBy=DatePlayed&StartIndex=0&Limit=$trackLimit", token).response.contentAsString)
        val total = body.get("TotalRecordCount").asInt()
        val itemsLoaded = body.get("Items").size()
        assertTrue(itemsLoaded >= total, "hasNextPage (itemsLoaded < total) must be false after the one personalized page")
    }

    // Regression: musicSurfaceFilter (audiobooks/red-lines) runs AFTER the random-fill draws
    // candidates, so a draw landing on audiobook-genre tracks used to silently under-fill the
    // page below Limit. Most of the library here is tagged Audiobook, leaving exactly
    // `trackLimit` real music tracks — the random-fill must retry until it surfaces all of them.
    @Test
    fun `random-fill retries past audiobook-genre tracks until the page is actually full`() {
        // Other test classes share this Testcontainers Postgres instance and may have already
        // created an "Audiobook" genre row (genres.name is UNIQUE) — upsert instead of a bare
        // insert so this test is independent of suite-wide execution order.
        val genreId =
            jdbc.queryForObject(
                "INSERT INTO core_v2_library.genres (id, name) VALUES (?, ?) " +
                    "ON CONFLICT (name) DO UPDATE SET name = excluded.name RETURNING id",
                UUID::class.java,
                UUID.randomUUID(),
                "Audiobook",
            )
        // Tag only this test's own seeded tracks, never rows another test class might own.
        val audiobookTrackCount = totalTracksInLibrary - trackLimit
        seededTrackIds.take(audiobookTrackCount).forEach { trackId ->
            jdbc.update(
                "INSERT INTO core_v2_library.entity_genres (entity_id, genre_id) VALUES (?, ?)",
                trackId,
                genreId,
            )
        }

        val result = get("/Items?IncludeItemTypes=Audio&SortBy=DatePlayed&StartIndex=0&Limit=$trackLimit", token)
        assertEquals(200, result.response.status)
        val body = objectMapper.readTree(result.response.contentAsString)
        assertEquals(
            trackLimit,
            body.get("Items").size(),
            "page must still hold exactly Limit non-audiobook tracks despite most of the library being filtered",
        )
    }

    // #266: the personalized (SortBy=DatePlayed) tracks branch used to bail whenever ExcludeGenres
    // was present, and the PWA ALWAYS sends ExcludeGenres=audiobook,audiobooks — so "Recently Played"
    // on Songs silently degraded to the deterministic full-library browse for every real user (the
    // album/artist branches were already fixed; the tracks leg was still dead). The branch must now
    // fire with ExcludeGenres set (exclusion happens inside buildPersonalizedTracks), reporting the
    // bounded personalized page size, not the full-library deterministic-fallback count.
    @Test
    fun `personalized tracks branch fires even when ExcludeGenres is set`() {
        val result =
            get(
                "/Items?IncludeItemTypes=Audio&SortBy=DatePlayed&StartIndex=0&Limit=$trackLimit&ExcludeGenres=audiobook,audiobooks",
                token,
            )
        assertEquals(200, result.response.status)
        val body = objectMapper.readTree(result.response.contentAsString)
        assertEquals(trackLimit, body.get("Items").size(), "personalized page must hold exactly Limit items with ExcludeGenres set")
        assertEquals(
            trackLimit,
            body.get("TotalRecordCount").asInt(),
            "TotalRecordCount must be the bounded personalized size, not the $totalTracksInLibrary-track deterministic " +
                "fallback — proving the branch fired despite a non-empty ExcludeGenres",
        )
    }
}
