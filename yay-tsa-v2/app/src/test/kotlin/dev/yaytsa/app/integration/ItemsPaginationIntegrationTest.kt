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
import jakarta.persistence.EntityManagerFactory
import org.hibernate.SessionFactory
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import java.time.Instant
import java.util.UUID

class ItemsPaginationIntegrationTest : HttpIntegrationTestBase() {
    @Autowired
    lateinit var authUseCases: AuthUseCases

    @Autowired
    lateinit var jdbc: JdbcTemplate

    @Autowired
    lateinit var entityManagerFactory: EntityManagerFactory

    private lateinit var token: String
    private lateinit var userId: String

    @BeforeEach
    fun seed() {
        userId = UUID.randomUUID().toString()
        token = UUID.randomUUID().toString()
        val uid = UserId(userId)
        val now = Instant.now()
        authUseCases.execute(
            CreateUser(uid, "page-${userId.take(8)}", "testpassword", "Test", null, false),
            CommandContext(uid, ProtocolId("JELLYFIN"), now, IdempotencyKey(UUID.randomUUID().toString()), AggregateVersion.INITIAL),
        )
        authUseCases.execute(
            CreateApiToken(uid, ApiTokenId(UUID.randomUUID().toString()), token, DeviceId("test"), "Test", null),
            CommandContext(uid, ProtocolId("JELLYFIN"), now, IdempotencyKey(UUID.randomUUID().toString()), AggregateVersion(1)),
        )
        repeat(3) {
            val id = UUID.randomUUID()
            jdbc.update(
                "INSERT INTO core_v2_library.entities (id, entity_type, name, sort_name, search_text) VALUES (?,?,?,?,?)",
                id,
                "ARTIST",
                "PageArtist-$it-${id.toString().take(6)}",
                "pageartist-$it",
                "pageartist",
            )
            jdbc.update("INSERT INTO core_v2_library.artists (entity_id) VALUES (?)", id)
        }
    }

    @Test
    fun `Artists TotalRecordCount is the DB count, not the page size`() {
        val result = get("/Artists?Limit=1", token)
        assertEquals(200, result.response.status)
        val body = objectMapper.readTree(result.response.contentAsString)
        val total = body.get("TotalRecordCount").asInt()
        val returned = body.get("Items").size()
        assertEquals(1, returned, "Limit=1 must return one page item")
        assertTrue(total >= 3, "TotalRecordCount must reflect all artists (>=3), was $total — infinite scroll truncates")
    }

    @Test
    fun `search TotalRecordCount sums matches across types, not page size`() {
        val result = get("/Items?SearchTerm=PageArtist&Limit=1", token)
        assertEquals(200, result.response.status)
        val body = objectMapper.readTree(result.response.contentAsString)
        assertTrue(body.get("TotalRecordCount").asInt() >= 3, "search count must sum all matching artists")
        assertTrue(body.get("Items").size() <= 1, "page must respect Limit=1")
    }

    @Test
    fun `oversized Limit is capped and does not error`() {
        val result = get("/Items?SearchTerm=PageArtist&Limit=100000000", token)
        assertEquals(200, result.response.status, "huge Limit must be coerced, not OOM/500")
    }

    @Test
    fun `songs honor SortName ascending and descending (not all the same)`() {
        val tag = "zzsort-${UUID.randomUUID().toString().take(6)}"
        listOf("a", "m", "z").forEach { s ->
            val id = UUID.randomUUID()
            jdbc.update(
                "INSERT INTO core_v2_library.entities (id, entity_type, name, sort_name, source_path, search_text) VALUES (?,?,?,?,?,?)",
                id,
                "TRACK",
                "$tag-$s",
                "$tag-$s",
                "/sorttest/$id.flac",
                tag,
            )
            jdbc.update("INSERT INTO core_v2_library.audio_tracks (entity_id, duration_ms) VALUES (?,?)", id, 100000L)
        }

        fun ordered(order: String): List<String> {
            val body =
                objectMapper.readTree(
                    get("/Items?IncludeItemTypes=Audio&Recursive=true&Limit=200&SortBy=SortName&SortOrder=$order", token).response.contentAsString,
                )
            return body.get("Items").map { it.get("Name").asText() }.filter { it.startsWith(tag) }
        }
        assertEquals(listOf("$tag-a", "$tag-m", "$tag-z"), ordered("Ascending"))
        assertEquals(listOf("$tag-z", "$tag-m", "$tag-a"), ordered("Descending"), "Descending must reverse, not echo Ascending")
    }

    @Test
    fun `ExcludeGenres drops audiobook tracks from Items and TotalRecordCount`() {
        val tag = "excl-${UUID.randomUUID().toString().take(6)}"
        val audiobookGenreId = ensureGenre("Audiobook")

        fun seedTrack(
            suffix: String,
            genreId: UUID?,
        ) {
            val id = UUID.randomUUID()
            jdbc.update(
                "INSERT INTO core_v2_library.entities (id, entity_type, name, sort_name, source_path, search_text) VALUES (?,?,?,?,?,?)",
                id,
                "TRACK",
                "$tag-$suffix",
                "$tag-$suffix",
                "/excltest/$id.flac",
                tag,
            )
            jdbc.update("INSERT INTO core_v2_library.audio_tracks (entity_id, duration_ms) VALUES (?,?)", id, 100000L)
            if (genreId != null) {
                jdbc.update("INSERT INTO core_v2_library.entity_genres (entity_id, genre_id) VALUES (?,?)", id, genreId)
            }
        }

        seedTrack("music-a", null)
        seedTrack("music-b", null)
        seedTrack("book-a", audiobookGenreId)
        seedTrack("book-b", audiobookGenreId)

        fun namesAndTotal(query: String): Pair<List<String>, Int> {
            val body = objectMapper.readTree(get(query, token).response.contentAsString)
            val names = body.get("Items").map { it.get("Name").asText() }.filter { it.startsWith(tag) }
            return names to body.get("TotalRecordCount").asInt()
        }

        val (allNames, allTotal) = namesAndTotal("/Items?IncludeItemTypes=Audio&Recursive=true&Limit=500&SortBy=SortName")
        assertEquals(setOf("$tag-music-a", "$tag-music-b", "$tag-book-a", "$tag-book-b"), allNames.toSet(), "baseline includes audiobooks")

        val (filteredNames, filteredTotal) =
            namesAndTotal("/Items?IncludeItemTypes=Audio&Recursive=true&Limit=500&SortBy=SortName&ExcludeGenres=Audiobook")
        assertEquals(setOf("$tag-music-a", "$tag-music-b"), filteredNames.toSet(), "ExcludeGenres=Audiobook must remove audiobook tracks")
        assertTrue(filteredNames.none { it.contains("book") }, "no audiobook track may leak through")
        assertEquals(
            allTotal - 2,
            filteredTotal,
            "TotalRecordCount must drop by the two excluded audiobook tracks (all=$allTotal, filtered=$filteredTotal)",
        )
    }

    private fun ensureGenre(name: String): UUID {
        jdbc.update("INSERT INTO core_v2_library.genres (id, name) VALUES (?,?) ON CONFLICT (name) DO NOTHING", UUID.randomUUID(), name)
        return jdbc.queryForObject("SELECT id FROM core_v2_library.genres WHERE name = ?", UUID::class.java, name)!!
    }

    @Test
    fun `ExcludeGenres drops audiobook-only albums and artists but keeps music ones`() {
        val tag = "exclaa-${UUID.randomUUID().toString().take(6)}"
        val audiobookGenreId = ensureGenre("Audiobook")

        fun seedArtist(suffix: String): UUID {
            val id = UUID.randomUUID()
            jdbc.update(
                "INSERT INTO core_v2_library.entities (id, entity_type, name, sort_name, search_text) VALUES (?,?,?,?,?)",
                id,
                "ARTIST",
                "$tag-$suffix",
                "$tag-$suffix",
                tag,
            )
            jdbc.update("INSERT INTO core_v2_library.artists (entity_id) VALUES (?)", id)
            return id
        }

        fun seedAlbum(
            suffix: String,
            artistId: UUID,
        ): UUID {
            val id = UUID.randomUUID()
            jdbc.update(
                "INSERT INTO core_v2_library.entities (id, entity_type, name, sort_name, search_text) VALUES (?,?,?,?,?)",
                id,
                "ALBUM",
                "$tag-$suffix",
                "$tag-$suffix",
                tag,
            )
            jdbc.update("INSERT INTO core_v2_library.albums (entity_id, artist_id) VALUES (?,?)", id, artistId)
            return id
        }

        fun seedTrack(
            suffix: String,
            albumId: UUID,
            artistId: UUID,
            genreId: UUID?,
        ) {
            val id = UUID.randomUUID()
            jdbc.update(
                "INSERT INTO core_v2_library.entities (id, entity_type, name, sort_name, source_path, search_text) VALUES (?,?,?,?,?,?)",
                id,
                "TRACK",
                "$tag-$suffix",
                "$tag-$suffix",
                "/exclaa/$id.flac",
                tag,
            )
            jdbc.update(
                "INSERT INTO core_v2_library.audio_tracks (entity_id, album_id, album_artist_id, duration_ms) VALUES (?,?,?,?)",
                id,
                albumId,
                artistId,
                100000L,
            )
            if (genreId != null) {
                jdbc.update("INSERT INTO core_v2_library.entity_genres (entity_id, genre_id) VALUES (?,?)", id, genreId)
            }
        }

        val musicArtist = seedArtist("music-artist")
        val bookArtist = seedArtist("book-artist")
        val musicAlbum = seedAlbum("music-album", musicArtist)
        val bookAlbum = seedAlbum("book-album", bookArtist)
        seedTrack("music-track", musicAlbum, musicArtist, null)
        seedTrack("book-track", bookAlbum, bookArtist, audiobookGenreId)

        fun names(query: String): Set<String> {
            val body = objectMapper.readTree(get(query, token).response.contentAsString)
            return body
                .get("Items")
                .map { it.get("Name").asText() }
                .filter { it.startsWith(tag) }
                .toSet()
        }

        assertEquals(
            setOf("$tag-music-album", "$tag-book-album"),
            names("/Items?IncludeItemTypes=MusicAlbum&Limit=500"),
            "baseline album browse includes audiobook album",
        )
        assertEquals(
            setOf("$tag-music-album"),
            names("/Items?IncludeItemTypes=MusicAlbum&Limit=500&ExcludeGenres=Audiobook"),
            "ExcludeGenres=Audiobook must drop the audiobook-only album, keep the music album",
        )

        assertEquals(
            setOf("$tag-music-artist", "$tag-book-artist"),
            names("/Items?IncludeItemTypes=MusicArtist&Limit=500"),
            "baseline artist browse includes audiobook artist",
        )
        assertEquals(
            setOf("$tag-music-artist"),
            names("/Items?IncludeItemTypes=MusicArtist&Limit=500&ExcludeGenres=Audiobook"),
            "ExcludeGenres=Audiobook must drop the audiobook-only artist, keep the music artist",
        )
    }

    @Test
    fun `ExcludeGenres keeps a mixed album and artist that also has music tracks`() {
        val tag = "exclmix-${UUID.randomUUID().toString().take(6)}"
        val audiobookGenreId = ensureGenre("Audiobook")

        val artistId = UUID.randomUUID()
        jdbc.update(
            "INSERT INTO core_v2_library.entities (id, entity_type, name, sort_name, search_text) VALUES (?,?,?,?,?)",
            artistId,
            "ARTIST",
            "$tag-artist",
            "$tag-artist",
            tag,
        )
        jdbc.update("INSERT INTO core_v2_library.artists (entity_id) VALUES (?)", artistId)

        val albumId = UUID.randomUUID()
        jdbc.update(
            "INSERT INTO core_v2_library.entities (id, entity_type, name, sort_name, search_text) VALUES (?,?,?,?,?)",
            albumId,
            "ALBUM",
            "$tag-album",
            "$tag-album",
            tag,
        )
        jdbc.update("INSERT INTO core_v2_library.albums (entity_id, artist_id) VALUES (?,?)", albumId, artistId)

        fun seedTrack(
            suffix: String,
            genreId: UUID?,
        ) {
            val id = UUID.randomUUID()
            jdbc.update(
                "INSERT INTO core_v2_library.entities (id, entity_type, name, sort_name, source_path, search_text) VALUES (?,?,?,?,?,?)",
                id,
                "TRACK",
                "$tag-$suffix",
                "$tag-$suffix",
                "/exclmix/$id.flac",
                tag,
            )
            jdbc.update(
                "INSERT INTO core_v2_library.audio_tracks (entity_id, album_id, album_artist_id, duration_ms) VALUES (?,?,?,?)",
                id,
                albumId,
                artistId,
                100000L,
            )
            if (genreId != null) {
                jdbc.update("INSERT INTO core_v2_library.entity_genres (entity_id, genre_id) VALUES (?,?)", id, genreId)
            }
        }

        seedTrack("book-track", audiobookGenreId)
        seedTrack("music-track", null)

        fun names(query: String): Set<String> {
            val body = objectMapper.readTree(get(query, token).response.contentAsString)
            return body
                .get("Items")
                .map { it.get("Name").asText() }
                .filter { it.startsWith(tag) }
                .toSet()
        }

        assertEquals(
            setOf("$tag-album"),
            names("/Items?IncludeItemTypes=MusicAlbum&Limit=500&ExcludeGenres=Audiobook"),
            "a mixed album with at least one non-audiobook track must be kept",
        )
        assertEquals(
            setOf("$tag-artist"),
            names("/Items?IncludeItemTypes=MusicArtist&Limit=500&ExcludeGenres=Audiobook"),
            "a mixed artist with at least one non-audiobook track must be kept",
        )
    }

    @Test
    fun `favorites honor custom order, date liked, and name sort (not all the same)`() {
        val tag = "favsort-${UUID.randomUUID().toString().take(6)}"
        val baseTime = Instant.parse("2026-01-01T00:00:00Z")
        // (suffix, position, favoritedAt-offset-minutes): name-asc=a,m,z; position-asc=m,z,a; dateLiked-desc=a,z,m
        val seedRows = listOf(Triple("a", 2, 30L), Triple("m", 0, 0L), Triple("z", 1, 15L))
        jdbc.update("INSERT INTO core_v2_preferences.user_preferences (user_id, version) VALUES (?, 0)", userId)
        seedRows.forEach { (s, position, offset) ->
            val id = UUID.randomUUID()
            jdbc.update(
                "INSERT INTO core_v2_library.entities (id, entity_type, name, sort_name, source_path, search_text) VALUES (?,?,?,?,?,?)",
                id,
                "TRACK",
                "$tag-$s",
                "$tag-$s",
                "/favsort/$id.flac",
                tag,
            )
            jdbc.update("INSERT INTO core_v2_library.audio_tracks (entity_id, duration_ms) VALUES (?,?)", id, 100000L)
            jdbc.update(
                "INSERT INTO core_v2_preferences.favorites (user_id, track_id, favorited_at, position) VALUES (?,?,?,?)",
                userId,
                id.toString(),
                java.sql.Timestamp.from(baseTime.plusSeconds(offset * 60)),
                position,
            )
        }

        fun favOrder(query: String): List<String> {
            val body =
                objectMapper.readTree(
                    get("/Items?IsFavorite=true&Limit=200&$query", token).response.contentAsString,
                )
            return body.get("Items").map { it.get("Name").asText() }.filter { it.startsWith(tag) }
        }
        assertEquals(
            listOf("$tag-m", "$tag-z", "$tag-a"),
            favOrder("SortBy=FavoritePosition&SortOrder=Ascending"),
            "Custom Order must follow stored position",
        )
        assertEquals(
            listOf("$tag-a", "$tag-z", "$tag-m"),
            favOrder("SortBy=DateFavorited&SortOrder=Descending"),
            "Date Liked must order by favoritedAt, not echo custom order",
        )
        assertEquals(
            listOf("$tag-a", "$tag-m", "$tag-z"),
            favOrder("SortBy=SortName&SortOrder=Ascending"),
            "Name sort must order by track name, not echo custom order",
        )
    }

    @Test
    fun `reorder persists when client sends only the resolvable subset of favorites`() {
        val tag = "favreorder-${UUID.randomUUID().toString().take(6)}"
        val now = Instant.parse("2026-02-01T00:00:00Z")
        jdbc.update("INSERT INTO core_v2_preferences.user_preferences (user_id, version) VALUES (?, 0)", userId)

        // Three real favorites (positions 0,1,2) the client can see...
        val ids = mutableMapOf<String, String>()
        listOf("a", "b", "c").forEachIndexed { index, s ->
            val id = UUID.randomUUID()
            ids[s] = id.toString()
            jdbc.update(
                "INSERT INTO core_v2_library.entities (id, entity_type, name, sort_name, source_path, search_text) VALUES (?,?,?,?,?,?)",
                id,
                "TRACK",
                "$tag-$s",
                "$tag-$s",
                "/favreorder/$id.flac",
                tag,
            )
            jdbc.update("INSERT INTO core_v2_library.audio_tracks (entity_id, duration_ms) VALUES (?,?)", id, 100000L)
            jdbc.update(
                "INSERT INTO core_v2_preferences.favorites (user_id, track_id, favorited_at, position) VALUES (?,?,?,?)",
                userId,
                id.toString(),
                java.sql.Timestamp.from(now),
                index,
            )
        }
        // ...plus one favorite whose track has vanished from the library: it is stored but never
        // listed, so the client cannot include it in a reorder. The old permutation check rejected
        // the whole reorder because of it.
        jdbc.update(
            "INSERT INTO core_v2_preferences.favorites (user_id, track_id, favorited_at, position) VALUES (?,?,?,?)",
            userId,
            UUID.randomUUID().toString(),
            java.sql.Timestamp.from(now),
            3,
        )

        fun customOrder(): List<String> {
            val body =
                objectMapper.readTree(
                    get("/Items?IsFavorite=true&Limit=200&SortBy=FavoritePosition&SortOrder=Ascending", token).response.contentAsString,
                )
            return body.get("Items").map { it.get("Name").asText() }.filter { it.startsWith(tag) }
        }

        assertEquals(listOf("$tag-a", "$tag-b", "$tag-c"), customOrder(), "precondition: initial custom order")

        // Client sends only the three resolvable ids it can see, reversed.
        val reorder =
            post(
                "/Items/FavoriteOrder",
                mapOf("UserId" to userId, "ItemIds" to listOf(ids["c"], ids["b"], ids["a"])),
                token,
            )
        assertEquals(200, reorder.response.status, "partial reorder must succeed despite the vanished favorite")

        assertEquals(
            listOf("$tag-c", "$tag-b", "$tag-a"),
            customOrder(),
            "new custom order must persist after reordering the resolvable subset",
        )
    }

    @Test
    fun `album items expose artist name and id across browse, ids, getItem and search`() {
        val tag = "albart-${UUID.randomUUID().toString().take(6)}"
        val artistId = UUID.randomUUID()
        jdbc.update(
            "INSERT INTO core_v2_library.entities (id, entity_type, name, sort_name, search_text) VALUES (?,?,?,?,?)",
            artistId,
            "ARTIST",
            "$tag Artist",
            "$tag artist",
            tag,
        )
        jdbc.update("INSERT INTO core_v2_library.artists (entity_id) VALUES (?)", artistId)

        val albumIds =
            (0..2).map { i ->
                val id = UUID.randomUUID()
                jdbc.update(
                    "INSERT INTO core_v2_library.entities (id, entity_type, name, sort_name, search_text) VALUES (?,?,?,?,?)",
                    id,
                    "ALBUM",
                    "$tag Album $i",
                    "$tag album $i",
                    tag,
                )
                jdbc.update("INSERT INTO core_v2_library.albums (entity_id, artist_id) VALUES (?,?)", id, artistId)
                id.toString()
            }

        fun assertArtist(album: JsonNode) {
            assertEquals(listOf("$tag Artist"), album.get("Artists").map { it.asText() }, "Artists must carry the album artist name")
            val items = album.get("ArtistItems")
            assertEquals(1, items.size(), "ArtistItems must have exactly the one album artist")
            assertEquals("$tag Artist", items[0].get("Name").asText())
            assertEquals(artistId.toString(), items[0].get("Id").asText(), "ArtistItems Id must be the artist entity id")
        }

        fun albumsFrom(query: String): List<JsonNode> =
            objectMapper
                .readTree(get(query, token).response.contentAsString)
                .get("Items")
                .filter { it.get("Type").asText() == "MusicAlbum" && it.get("Name").asText().startsWith(tag) }

        val browse = albumsFrom("/Items?IncludeItemTypes=MusicAlbum&Limit=500")
        assertEquals(3, browse.size, "browse must return all three seeded albums")
        browse.forEach { assertArtist(it) }

        albumsFrom("/Items?Ids=${albumIds.joinToString(",")}").forEach { assertArtist(it) }
        albumsFrom("/Items?SearchTerm=$tag&Limit=500").forEach { assertArtist(it) }

        val single = objectMapper.readTree(get("/Items/${albumIds.first()}", token).response.contentAsString)
        assertArtist(single)
    }

    @Test
    fun `album page query count is constant regardless of album count (no per-album artist N plus 1)`() {
        val tag = "albn1-${UUID.randomUUID().toString().take(6)}"

        fun seedArtistWithAlbums(count: Int): String {
            val artistId = UUID.randomUUID()
            jdbc.update(
                "INSERT INTO core_v2_library.entities (id, entity_type, name, sort_name, search_text) VALUES (?,?,?,?,?)",
                artistId,
                "ARTIST",
                "$tag-${artistId.toString().take(4)}",
                "$tag artist",
                tag,
            )
            jdbc.update("INSERT INTO core_v2_library.artists (entity_id) VALUES (?)", artistId)
            repeat(count) { i ->
                val id = UUID.randomUUID()
                jdbc.update(
                    "INSERT INTO core_v2_library.entities (id, entity_type, name, sort_name, search_text) VALUES (?,?,?,?,?)",
                    id,
                    "ALBUM",
                    "$tag album ${"%03d".format(i)} ${id.toString().take(4)}",
                    "$tag album ${"%03d".format(i)}",
                    tag,
                )
                jdbc.update("INSERT INTO core_v2_library.albums (entity_id, artist_id) VALUES (?,?)", id, artistId)
            }
            return artistId.toString()
        }

        // Two artists with very different album counts; the ArtistIds branch returns exactly that
        // artist's albums, isolating the page from other tests' data so the only variable is size.
        val smallArtist = seedArtistWithAlbums(3)
        val largeArtist = seedArtistWithAlbums(15)

        val stats = entityManagerFactory.unwrap(SessionFactory::class.java).statistics
        stats.isStatisticsEnabled = true

        fun pageQueryCount(artistId: String): Long {
            val url = "/Items?IncludeItemTypes=MusicAlbum&ArtistIds=$artistId"
            // Warm once: the first request after context boot pays one-off setup queries that are
            // unrelated to page size. Measure the second, steady-state request.
            get(url, token)
            stats.clear()
            val res = get(url, token)
            assertEquals(200, res.response.status)
            return stats.prepareStatementCount
        }

        val small = pageQueryCount(smallArtist)
        val large = pageQueryCount(largeArtist)
        // Batched: large ≈ small (artist names resolved in one query per page). A per-album artist
        // N+1 would add ~2 queries per extra album (here +24 for 12 more albums), so a small constant
        // tolerance distinguishes "batched" from "N+1" without being brittle to ±1 plan noise.
        assertTrue(
            large - small <= 2,
            "a 15-album page must not issue materially more queries than a 3-album page " +
                "(small=$small, large=$large); growth here means the Album->artist N+1 has returned",
        )
    }
}
