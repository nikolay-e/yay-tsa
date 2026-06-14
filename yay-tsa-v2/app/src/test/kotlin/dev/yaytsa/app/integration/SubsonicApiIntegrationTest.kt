package dev.yaytsa.app.integration

import com.fasterxml.jackson.databind.JsonNode
import dev.yaytsa.application.auth.AuthUseCases
import dev.yaytsa.domain.auth.CreateUser
import dev.yaytsa.shared.AggregateVersion
import dev.yaytsa.shared.CommandContext
import dev.yaytsa.shared.CommandResult
import dev.yaytsa.shared.IdempotencyKey
import dev.yaytsa.shared.ProtocolId
import dev.yaytsa.shared.UserId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.crypto.bcrypt.BCrypt
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders
import org.w3c.dom.Element
import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import javax.xml.parsers.DocumentBuilderFactory

class SubsonicApiIntegrationTest : HttpIntegrationTestBase() {
    @Autowired
    lateinit var authUseCases: AuthUseCases

    @Autowired
    lateinit var jdbc: JdbcTemplate

    private lateinit var username: String
    private lateinit var password: String
    private lateinit var userId: String

    private lateinit var artistId: String
    private lateinit var albumOldId: String
    private lateinit var albumNewId: String
    private lateinit var trackIds: List<String>

    @BeforeEach
    fun seedUserAndLibrary() {
        username = "subsonic-${UUID.randomUUID().toString().take(8)}"
        password = "testpass123"
        userId = UUID.randomUUID().toString()
        val uid = UserId(userId)
        val now = Instant.now()

        val passwordHash = BCrypt.hashpw(password, BCrypt.gensalt(4))
        val createCmd = CreateUser(uid, username, passwordHash, null, null, false)
        val ctx = CommandContext(uid, ProtocolId("JELLYFIN"), now, IdempotencyKey(UUID.randomUUID().toString()), AggregateVersion.INITIAL)
        val seeded = authUseCases.execute(createCmd, ctx)
        check(seeded is CommandResult.Success) { "Seed user failed: $seeded" }

        artistId = insertArtist("SubArtist-${UUID.randomUUID().toString().take(6)}")
        albumOldId = insertAlbum("SubAlbumOld-${UUID.randomUUID().toString().take(6)}", artistId, now.plusSeconds(86_400), 1999)
        albumNewId = insertAlbum("SubAlbumNew-${UUID.randomUUID().toString().take(6)}", artistId, now.plusSeconds(172_800), 2021)
        trackIds =
            listOf(
                insertTrack("SubTrackA-${UUID.randomUUID().toString().take(6)}", albumOldId, artistId, 1),
                insertTrack("SubTrackB-${UUID.randomUUID().toString().take(6)}", albumOldId, artistId, 2),
                insertTrack("SubTrackC-${UUID.randomUUID().toString().take(6)}", albumNewId, artistId, 1),
            )
    }

    private fun insertArtist(name: String): String {
        val id = UUID.randomUUID()
        jdbc.update(
            "INSERT INTO core_v2_library.entities (id, entity_type, name, sort_name, search_text) VALUES (?,?,?,?,?)",
            id,
            "ARTIST",
            name,
            name.lowercase(),
            name.lowercase(),
        )
        jdbc.update("INSERT INTO core_v2_library.artists (entity_id) VALUES (?)", id)
        return id.toString()
    }

    private fun insertAlbum(
        name: String,
        artistId: String,
        createdAt: Instant,
        year: Int,
    ): String {
        val id = UUID.randomUUID()
        jdbc.update(
            "INSERT INTO core_v2_library.entities (id, entity_type, name, sort_name, search_text, created_at) VALUES (?,?,?,?,?,?)",
            id,
            "ALBUM",
            name,
            name.lowercase(),
            name.lowercase(),
            Timestamp.from(createdAt),
        )
        jdbc.update(
            "INSERT INTO core_v2_library.albums (entity_id, artist_id, release_date, total_tracks) VALUES (?,?,?,?)",
            id,
            UUID.fromString(artistId),
            java.sql.Date.valueOf(LocalDate.of(year, 6, 1)),
            2,
        )
        return id.toString()
    }

    private fun insertTrack(
        name: String,
        albumId: String,
        artistId: String,
        trackNumber: Int,
    ): String {
        val id = UUID.randomUUID()
        jdbc.update(
            "INSERT INTO core_v2_library.entities (id, entity_type, name, sort_name, search_text) VALUES (?,?,?,?,?)",
            id,
            "TRACK",
            name,
            name.lowercase(),
            name.lowercase(),
        )
        jdbc.update(
            "INSERT INTO core_v2_library.audio_tracks (entity_id, album_id, album_artist_id, track_number, duration_ms) VALUES (?,?,?,?,?)",
            id,
            UUID.fromString(albumId),
            UUID.fromString(artistId),
            trackNumber,
            300_000L,
        )
        return id.toString()
    }

    private fun restGet(
        path: String,
        vararg params: Pair<String, String>,
        authenticated: Boolean = true,
    ): MvcResult {
        val builder = MockMvcRequestBuilders.get("/rest/$path")
        if (authenticated) {
            builder.param("u", username).param("p", password)
        }
        builder.param("v", "1.16.1").param("c", "test")
        params.forEach { (key, value) -> builder.param(key, value) }
        return mockMvc.perform(builder).andReturn()
    }

    private fun parseXml(content: String) =
        DocumentBuilderFactory
            .newInstance()
            .newDocumentBuilder()
            .parse(content.byteInputStream())

    private fun xmlRoot(result: MvcResult): Element {
        assertEquals(200, result.response.status)
        assertTrue(result.response.contentType?.contains("xml") ?: false, "expected XML, got ${result.response.contentType}")
        return parseXml(result.response.contentAsString).documentElement
    }

    private fun jsonBody(result: MvcResult): JsonNode {
        assertEquals(200, result.response.status)
        return objectMapper.readTree(result.response.contentAsString).get("subsonic-response")
    }

    private fun xmlErrorCode(result: MvcResult): Int {
        val root = xmlRoot(result)
        assertEquals("failed", root.getAttribute("status"))
        val error = root.getElementsByTagName("error").item(0) as Element
        return error.getAttribute("code").toInt()
    }

    private fun elementsOf(
        parent: Element,
        tag: String,
    ): List<Element> {
        val nodes = parent.getElementsByTagName(tag)
        return (0 until nodes.length).map { nodes.item(it) as Element }
    }

    // --- System & auth ---

    @Test
    fun `ping returns ok`() {
        val result = restGet("ping")
        assertEquals(200, result.response.status)
        assertEquals("ok", xmlRoot(result).getAttribute("status"))
    }

    @Test
    fun `ping returns XML by default`() {
        val result = restGet("ping")
        assertTrue(result.response.contentType?.contains("xml") ?: false)
    }

    @Test
    fun `ping with f=json returns JSON with ok status`() {
        val result = restGet("ping", "f" to "json")
        assertTrue(result.response.contentType?.contains("json") ?: false)
        assertEquals("ok", jsonBody(result).get("status").asText())
    }

    @Test
    fun `ping with f=jsonp and callback wraps JSON in callback`() {
        val result = restGet("ping", "f" to "jsonp", "callback" to "cb")
        assertEquals(200, result.response.status)
        assertTrue(result.response.contentType?.contains("javascript") ?: false)
        assertTrue(result.response.contentAsString.startsWith("cb("))
        assertTrue(result.response.contentAsString.endsWith(");"))
    }

    @Test
    fun `ping with f=jsonp without callback returns error instead of XML fallback`() {
        val result = restGet("ping", "f" to "jsonp")
        assertEquals(200, result.response.status)
        val body = objectMapper.readTree(result.response.contentAsString).get("subsonic-response")
        assertEquals("failed", body.get("status").asText())
        assertEquals(10, body.get("error").get("code").asInt())
    }

    @Test
    fun `request without credentials returns Subsonic envelope code 10 not HTTP 401`() {
        val result = restGet("getArtists", authenticated = false)
        assertEquals(200, result.response.status)
        assertEquals(10, xmlErrorCode(result))
    }

    @Test
    fun `token and salt auth returns code 41 telling client to fall back to password`() {
        val builder =
            MockMvcRequestBuilders
                .get("/rest/ping")
                .param("u", username)
                .param("t", "0f1e2d3c4b5a69788796a5b4c3d2e1f0")
                .param("s", "abcdef")
                .param("v", "1.16.1")
                .param("c", "test")
        val result = mockMvc.perform(builder).andReturn()
        assertEquals(200, result.response.status)
        assertEquals(41, xmlErrorCode(result))
    }

    @Test
    fun `wrong password returns HTTP 200 with code 40 in XML`() {
        val builder =
            MockMvcRequestBuilders
                .get("/rest/ping")
                .param("u", username)
                .param("p", "wrong-password")
                .param("v", "1.16.1")
                .param("c", "test")
        val result = mockMvc.perform(builder).andReturn()
        assertEquals(200, result.response.status)
        assertEquals(40, xmlErrorCode(result))
    }

    @Test
    fun `wrong password returns HTTP 200 with code 40 in JSON`() {
        val builder =
            MockMvcRequestBuilders
                .get("/rest/ping")
                .param("u", username)
                .param("p", "wrong-password")
                .param("f", "json")
                .param("v", "1.16.1")
                .param("c", "test")
        val result = mockMvc.perform(builder).andReturn()
        assertEquals(200, result.response.status)
        val body = jsonBody(result)
        assertEquals("failed", body.get("status").asText())
        assertEquals(40, body.get("error").get("code").asInt())
    }

    @Test
    fun `enc password with invalid hex returns code 40 not HTTP 500`() {
        val builder =
            MockMvcRequestBuilders
                .get("/rest/ping")
                .param("u", username)
                .param("p", "enc:zz-not-hex")
                .param("v", "1.16.1")
                .param("c", "test")
        val result = mockMvc.perform(builder).andReturn()
        assertEquals(200, result.response.status)
        assertEquals(40, xmlErrorCode(result))
    }

    @Test
    fun `enc hex-encoded password authenticates`() {
        val encoded = password.toByteArray(Charsets.UTF_8).joinToString("") { "%02x".format(it) }
        val builder =
            MockMvcRequestBuilders
                .get("/rest/ping")
                .param("u", username)
                .param("p", "enc:$encoded")
                .param("v", "1.16.1")
                .param("c", "test")
        val result = mockMvc.perform(builder).andReturn()
        assertEquals("ok", xmlRoot(result).getAttribute("status"))
    }

    @Test
    fun `getLicense returns valid`() {
        val result = restGet("getLicense")
        assertEquals(200, result.response.status)
        assertEquals("ok", xmlRoot(result).getAttribute("status"))
    }

    @Test
    fun `unknown rest endpoint returns envelope code 70 not problem json`() {
        val result = restGet("getVideos")
        assertEquals(200, result.response.status)
        assertEquals(70, xmlErrorCode(result))
    }

    @Test
    fun `bad uuid id returns envelope code 70 not HTTP 500`() {
        val result = restGet("getSong", "id" to "not-a-uuid")
        assertEquals(200, result.response.status)
        assertEquals(70, xmlErrorCode(result))
    }

    @Test
    fun `getUser for another username as non-admin returns code 50`() {
        val result = restGet("getUser", "username" to "someone-else")
        assertEquals(200, result.response.status)
        assertEquals(50, xmlErrorCode(result))
    }

    // --- XML shape ---

    @Test
    fun `getSong XML serializes scalar fields as attributes`() {
        val result = restGet("getSong", "id" to trackIds[0])
        val root = xmlRoot(result)
        assertEquals("ok", root.getAttribute("status"))
        val song = root.getElementsByTagName("song").item(0) as Element
        assertEquals(trackIds[0], song.getAttribute("id"))
        assertEquals(albumOldId, song.getAttribute("albumId"))
        assertEquals(0, song.getElementsByTagName("id").length, "id must be an attribute, not a child element")
        assertEquals(0, song.getElementsByTagName("title").length, "title must be an attribute, not a child element")
    }

    @Test
    fun `getSong JSON keeps fields as plain JSON properties`() {
        val result = restGet("getSong", "id" to trackIds[0], "f" to "json")
        val song = jsonBody(result).get("song")
        assertEquals(trackIds[0], song.get("id").asText())
        assertEquals(albumOldId, song.get("albumId").asText())
    }

    @Test
    fun `getArtists XML nests unwrapped index and artist elements with attributes`() {
        val result = restGet("getArtists")
        val root = xmlRoot(result)
        val indexes = elementsOf(root, "index")
        assertTrue(indexes.isNotEmpty(), "expected at least one index element")
        assertTrue(indexes.all { it.hasAttribute("name") }, "index elements must carry name attribute")
        val artistElements = elementsOf(root, "artist")
        assertTrue(artistElements.any { it.getAttribute("id") == artistId }, "seeded artist must be listed with id attribute")
    }

    // --- Browsing ---

    @Test
    fun `getIndexes returns indexes with seeded artist`() {
        val result = restGet("getIndexes")
        val root = xmlRoot(result)
        val indexesWrapper = root.getElementsByTagName("indexes")
        assertEquals(1, indexesWrapper.length)
        assertTrue(elementsOf(root, "artist").any { it.getAttribute("id") == artistId })
    }

    @Test
    fun `getMusicDirectory for artist lists albums as directories`() {
        val result = restGet("getMusicDirectory", "id" to artistId)
        val root = xmlRoot(result)
        val directory = root.getElementsByTagName("directory").item(0) as Element
        assertEquals(artistId, directory.getAttribute("id"))
        val children = elementsOf(directory, "child")
        assertEquals(2, children.size)
        assertTrue(children.all { it.getAttribute("isDir") == "true" })
        assertTrue(children.any { it.getAttribute("id") == albumOldId })
    }

    @Test
    fun `getMusicDirectory for album lists its tracks`() {
        val result = restGet("getMusicDirectory", "id" to albumOldId)
        val root = xmlRoot(result)
        val directory = root.getElementsByTagName("directory").item(0) as Element
        val children = elementsOf(directory, "child")
        assertEquals(2, children.size)
        assertTrue(children.all { it.getAttribute("isDir") == "false" })
        assertTrue(children.any { it.getAttribute("id") == trackIds[0] })
    }

    @Test
    fun `getAlbumList2 type=newest orders by creation date descending`() {
        val result = restGet("getAlbumList2", "type" to "newest", "size" to "500", "f" to "json")
        val albums = jsonBody(result).get("albumList2").get("album")
        val ids = (0 until albums.size()).map { albums.get(it).get("id").asText() }
        assertTrue(ids.indexOf(albumNewId) < ids.indexOf(albumOldId), "future-dated album must sort before the older one")
    }

    @Test
    fun `getAlbumList2 type=byYear filters by year range`() {
        val result = restGet("getAlbumList2", "type" to "byYear", "fromYear" to "2020", "toYear" to "2022", "size" to "500", "f" to "json")
        val albums = jsonBody(result).get("albumList2").get("album")
        val ids = (0 until albums.size()).map { albums.get(it).get("id").asText() }
        assertTrue(albumNewId in ids, "2021 album must match byYear 2020-2022")
        assertFalse(albumOldId in ids, "1999 album must not match byYear 2020-2022")
    }

    @Test
    fun `getAlbumList2 type=byYear without year params returns code 10`() {
        val result = restGet("getAlbumList2", "type" to "byYear")
        assertEquals(10, xmlErrorCode(result))
    }

    @Test
    fun `getAlbumList v1 returns album children with title attribute`() {
        val result = restGet("getAlbumList", "type" to "newest", "size" to "2")
        val root = xmlRoot(result)
        val albumList = root.getElementsByTagName("albumList")
        assertEquals(1, albumList.length)
        val albums = elementsOf(albumList.item(0) as Element, "album")
        assertEquals(2, albums.size)
        assertEquals(albumNewId, albums[0].getAttribute("id"))
        assertTrue(albums[0].hasAttribute("title"))
        assertEquals("true", albums[0].getAttribute("isDir"))
    }

    // --- Search ---

    @Test
    fun `search3 with empty query returns full library listing`() {
        val result = restGet("search3", "query" to "", "songCount" to "200", "albumCount" to "200", "artistCount" to "200", "f" to "json")
        val body = jsonBody(result).get("searchResult3")
        val songIds = body.get("song").let { songs -> (0 until songs.size()).map { songs.get(it).get("id").asText() } }
        assertTrue(trackIds.all { it in songIds }, "empty query must list library songs for offline sync")
        assertTrue(body.get("album").size() > 0)
        assertTrue(body.get("artist").size() > 0)
    }

    @Test
    fun `search3 honors songOffset paging`() {
        val page1 = jsonBody(restGet("search3", "query" to "", "songCount" to "1", "songOffset" to "0", "f" to "json"))
        val page2 = jsonBody(restGet("search3", "query" to "", "songCount" to "1", "songOffset" to "1", "f" to "json"))
        val first =
            page1
                .get("searchResult3")
                .get("song")
                .get(0)
                .get("id")
                .asText()
        val second =
            page2
                .get("searchResult3")
                .get("song")
                .get(0)
                .get("id")
                .asText()
        assertNotEquals(first, second, "songOffset must advance the page")
    }

    @Test
    fun `search3 with query matches seeded track`() {
        val trackName =
            jdbc.queryForObject(
                "SELECT name FROM core_v2_library.entities WHERE id = ?",
                String::class.java,
                UUID.fromString(trackIds[0]),
            )
        val result = restGet("search3", "query" to trackName!!.take(12), "f" to "json")
        val songs = jsonBody(result).get("searchResult3").get("song")
        assertTrue((0 until songs.size()).any { songs.get(it).get("id").asText() == trackIds[0] })
    }

    // --- Favorites ---

    @Test
    fun `star with repeated ids stars all tracks and getStarred2 returns them`() {
        val starResult = restGet("star", "id" to trackIds[0], "id" to trackIds[1])
        assertEquals("ok", xmlRoot(starResult).getAttribute("status"))

        val starred = jsonBody(restGet("getStarred2", "f" to "json")).get("starred2").get("song")
        val ids = (0 until starred.size()).map { starred.get(it).get("id").asText() }
        assertTrue(trackIds[0] in ids && trackIds[1] in ids)
        assertTrue((0 until starred.size()).all { starred.get(it).hasNonNull("starred") }, "starred timestamp must be set")
    }

    @Test
    fun `getStarred v1 returns starred songs`() {
        restGet("star", "id" to trackIds[2])
        val root = xmlRoot(restGet("getStarred"))
        val songs = elementsOf(root, "song")
        assertTrue(songs.any { it.getAttribute("id") == trackIds[2] })
    }

    @Test
    fun `unstar removes favorite and is idempotent`() {
        restGet("star", "id" to trackIds[0])
        val unstar1 = restGet("unstar", "id" to trackIds[0])
        assertEquals("ok", xmlRoot(unstar1).getAttribute("status"))
        val unstar2 = restGet("unstar", "id" to trackIds[0])
        assertEquals("ok", xmlRoot(unstar2).getAttribute("status"), "unstar of a non-favorite must stay ok")
        val starred = jsonBody(restGet("getStarred2", "f" to "json")).get("starred2")
        assertTrue(starred.get("song") == null || starred.get("song").size() == 0)
    }

    @Test
    fun `star with albumId only returns ok without corrupting favorites`() {
        val result = restGet("star", "albumId" to albumOldId)
        assertEquals("ok", xmlRoot(result).getAttribute("status"))
        val starred = jsonBody(restGet("getStarred2", "f" to "json")).get("starred2")
        assertTrue(starred.get("song") == null || starred.get("song").size() == 0)
    }

    // --- Playlists ---

    private fun createPlaylistWithSongs(
        name: String,
        songs: List<String>,
    ): String {
        val params = mutableListOf("name" to name, "f" to "json")
        val builder = MockMvcRequestBuilders.get("/rest/createPlaylist")
        builder
            .param("u", username)
            .param("p", password)
            .param("v", "1.16.1")
            .param("c", "test")
        params.forEach { (k, v) -> builder.param(k, v) }
        songs.forEach { builder.param("songId", it) }
        val result = mockMvc.perform(builder).andReturn()
        val body = jsonBody(result)
        assertEquals("ok", body.get("status").asText())
        return body.get("playlist").get("id").asText()
    }

    @Test
    fun `createPlaylist returns playlist detail with entries`() {
        val playlistId = createPlaylistWithSongs("pl-create-${UUID.randomUUID().toString().take(6)}", listOf(trackIds[0], trackIds[1]))
        val detail = jsonBody(restGet("getPlaylist", "id" to playlistId, "f" to "json")).get("playlist")
        assertEquals(2, detail.get("entry").size())
    }

    @Test
    fun `createPlaylist with playlistId replaces content instead of appending`() {
        val playlistId = createPlaylistWithSongs("pl-replace-${UUID.randomUUID().toString().take(6)}", listOf(trackIds[0], trackIds[1]))

        val builder = MockMvcRequestBuilders.get("/rest/createPlaylist")
        builder
            .param("u", username)
            .param("p", password)
            .param("v", "1.16.1")
            .param("c", "test")
        builder.param("playlistId", playlistId).param("songId", trackIds[2]).param("f", "json")
        val replaceResult = mockMvc.perform(builder).andReturn()
        assertEquals("ok", jsonBody(replaceResult).get("status").asText())

        val entries = jsonBody(restGet("getPlaylist", "id" to playlistId, "f" to "json")).get("playlist").get("entry")
        assertEquals(1, entries.size(), "createPlaylist with playlistId must redefine, not append")
        assertEquals(trackIds[2], entries.get(0).get("id").asText())
    }

    @Test
    fun `updatePlaylist adds and removes songs and renames`() {
        val playlistId = createPlaylistWithSongs("pl-update-${UUID.randomUUID().toString().take(6)}", listOf(trackIds[0], trackIds[1]))

        val builder = MockMvcRequestBuilders.get("/rest/updatePlaylist")
        builder
            .param("u", username)
            .param("p", password)
            .param("v", "1.16.1")
            .param("c", "test")
        builder
            .param("playlistId", playlistId)
            .param("name", "renamed-playlist")
            .param("songIndexToRemove", "0")
            .param("songIdToAdd", trackIds[2])
        val updateResult = mockMvc.perform(builder).andReturn()
        assertEquals("ok", xmlRoot(updateResult).getAttribute("status"))

        val detail = jsonBody(restGet("getPlaylist", "id" to playlistId, "f" to "json")).get("playlist")
        assertEquals("renamed-playlist", detail.get("name").asText())
        val entryIds = detail.get("entry").let { entries -> (0 until entries.size()).map { entries.get(it).get("id").asText() } }
        assertEquals(listOf(trackIds[1], trackIds[2]), entryIds)
    }

    @Test
    fun `updatePlaylist for missing playlist returns code 70`() {
        val result = restGet("updatePlaylist", "playlistId" to UUID.randomUUID().toString())
        assertEquals(70, xmlErrorCode(result))
    }

    // --- Playback ---

    @Test
    fun `scrobble records play history`() {
        val before =
            jdbc.queryForObject(
                "SELECT count(*) FROM core_v2_playback.play_history WHERE user_id = ?",
                Long::class.java,
                userId,
            ) ?: 0
        val result =
            restGet(
                "scrobble",
                "id" to trackIds[0],
                "time" to
                    Instant
                        .now()
                        .minusSeconds(300)
                        .toEpochMilli()
                        .toString(),
            )
        assertEquals("ok", xmlRoot(result).getAttribute("status"))
        val after =
            jdbc.queryForObject(
                "SELECT count(*) FROM core_v2_playback.play_history WHERE user_id = ?",
                Long::class.java,
                userId,
            ) ?: 0
        assertEquals(before + 1, after, "scrobble must persist a play history row")
    }

    @Test
    fun `savePlayQueue then getPlayQueue round-trips ids current and position`() {
        val builder = MockMvcRequestBuilders.get("/rest/savePlayQueue")
        builder
            .param("u", username)
            .param("p", password)
            .param("v", "1.16.1")
            .param("c", "test")
        builder
            .param("id", trackIds[0])
            .param("id", trackIds[1])
            .param("current", trackIds[1])
            .param("position", "42000")
        val saveResult = mockMvc.perform(builder).andReturn()
        assertEquals("ok", xmlRoot(saveResult).getAttribute("status"))

        val body = jsonBody(restGet("getPlayQueue", "f" to "json"))
        assertEquals("ok", body.get("status").asText())
        val playQueue = body.get("playQueue")
        assertEquals(trackIds[1], playQueue.get("current").asText())
        assertEquals(42000L, playQueue.get("position").asLong())
        assertEquals("test", playQueue.get("changedBy").asText())
        assertEquals(username, playQueue.get("username").asText())
        val entries = playQueue.get("entry")
        val entryIds = (0 until entries.size()).map { entries.get(it).get("id").asText() }
        assertEquals(listOf(trackIds[0], trackIds[1]), entryIds, "queue order must round-trip")
    }

    @Test
    fun `getPlayQueue without a saved queue returns ok with no playQueue`() {
        val body = jsonBody(restGet("getPlayQueue", "f" to "json"))
        assertEquals("ok", body.get("status").asText())
        assertTrue(body.get("playQueue") == null, "absent saved queue must yield ok-empty, not an error")
    }

    @Test
    fun `savePlayQueue overwrites the previous snapshot`() {
        val first = MockMvcRequestBuilders.get("/rest/savePlayQueue")
        first
            .param("u", username)
            .param("p", password)
            .param("v", "1.16.1")
            .param("c", "test")
        first
            .param("id", trackIds[0])
            .param("id", trackIds[1])
            .param("current", trackIds[0])
            .param("position", "1000")
        assertEquals("ok", xmlRoot(mockMvc.perform(first).andReturn()).getAttribute("status"))

        val second = MockMvcRequestBuilders.get("/rest/savePlayQueue")
        second
            .param("u", username)
            .param("p", password)
            .param("v", "1.16.1")
            .param("c", "test")
        second.param("id", trackIds[2]).param("current", trackIds[2]).param("position", "5000")
        assertEquals("ok", xmlRoot(mockMvc.perform(second).andReturn()).getAttribute("status"))

        val playQueue = jsonBody(restGet("getPlayQueue", "f" to "json")).get("playQueue")
        assertEquals(trackIds[2], playQueue.get("current").asText())
        assertEquals(5000L, playQueue.get("position").asLong())
        val entries = playQueue.get("entry")
        val entryIds = (0 until entries.size()).map { entries.get(it).get("id").asText() }
        assertEquals(listOf(trackIds[2]), entryIds, "later savePlayQueue must replace, not append")
    }

    @Test
    fun `getNowPlaying returns well-formed nowPlaying element`() {
        val result = restGet("getNowPlaying")
        val root = xmlRoot(result)
        assertEquals("ok", root.getAttribute("status"))
        assertEquals(1, root.getElementsByTagName("nowPlaying").length)
    }

    // --- OpenSubsonic extensions ---

    @Test
    fun `getOpenSubsonicExtensions advertises songLyrics and apiKeyAuthentication`() {
        val body = jsonBody(restGet("getOpenSubsonicExtensions", "f" to "json"))
        assertTrue(body.get("openSubsonic").asBoolean(), "responses must keep openSubsonic flag")
        val extensions = body.get("openSubsonicExtensions")
        val names = (0 until extensions.size()).map { extensions.get(it).get("name").asText() }
        assertTrue("songLyrics" in names)
        assertTrue("apiKeyAuthentication" in names)
        assertFalse("transcodeOffset" in names, "must not advertise unimplemented transcodeOffset")
        assertFalse("formPost" in names, "endpoints are GET-only, formPost must not be advertised")
        assertTrue(
            (0 until extensions.size()).all { extensions.get(it).get("versions").size() > 0 },
            "each extension entry must carry a versions array",
        )
    }

    // --- songLyrics: getLyricsBySongId ---

    private fun insertTrackWithSyncedSidecar(name: String): String {
        val id = UUID.randomUUID()
        val dir =
            java.nio.file.Files
                .createTempDirectory("subsonic-lyrics")
        val audioFile = dir.resolve("$name.flac")
        java.nio.file.Files
            .writeString(audioFile, "audio")
        val lyricsDir =
            java.nio.file.Files
                .createDirectory(dir.resolve(".lyrics"))
        java.nio.file.Files
            .writeString(lyricsDir.resolve("$name.lrc"), "[00:01.50]first line\n[00:03.00]second line\n")
        jdbc.update(
            "INSERT INTO core_v2_library.entities (id, entity_type, name, sort_name, search_text, source_path) VALUES (?,?,?,?,?,?)",
            id,
            "TRACK",
            name,
            name.lowercase(),
            name.lowercase(),
            audioFile.toString(),
        )
        jdbc.update(
            "INSERT INTO core_v2_library.audio_tracks (entity_id, album_id, album_artist_id, track_number, duration_ms) VALUES (?,?,?,?,?)",
            id,
            UUID.fromString(albumOldId),
            UUID.fromString(artistId),
            9,
            180_000L,
        )
        return id.toString()
    }

    @Test
    fun `getLyricsBySongId returns synced structured lyrics from a sidecar`() {
        val trackId = insertTrackWithSyncedSidecar("LyricTrack-${UUID.randomUUID().toString().take(6)}")
        val body = jsonBody(restGet("getLyricsBySongId", "id" to trackId, "f" to "json"))
        assertEquals("ok", body.get("status").asText())
        val structured = body.get("lyricsList").get("structuredLyrics")
        assertEquals(1, structured.size())
        val entry = structured.get(0)
        assertTrue(entry.get("synced").asBoolean(), "sidecar carries timestamps so synced must be true")
        val lines = entry.get("line")
        assertEquals(2, lines.size())
        assertEquals(1500L, lines.get(0).get("start").asLong())
        assertEquals("first line", lines.get(0).get("value").asText())
        assertEquals(3000L, lines.get(1).get("start").asLong())
    }

    @Test
    fun `getLyricsBySongId returns empty structuredLyrics when no lyrics exist`() {
        val body = jsonBody(restGet("getLyricsBySongId", "id" to trackIds[0], "f" to "json"))
        assertEquals("ok", body.get("status").asText())
        val structured = body.get("lyricsList").get("structuredLyrics")
        assertTrue(structured == null || structured.size() == 0, "absent lyrics must yield empty structuredLyrics, not an error")
    }

    @Test
    fun `getLyricsBySongId for unknown id returns not found code 70`() {
        val result = restGet("getLyricsBySongId", "id" to UUID.randomUUID().toString())
        assertEquals(70, xmlErrorCode(result))
    }

    // --- apiKeyAuthentication ---

    private fun issueApiToken(): String {
        val builder =
            MockMvcRequestBuilders
                .post("/Users/AuthenticateByName")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(mapOf("Username" to username, "Pw" to password)))
        val result = mockMvc.perform(builder).andReturn()
        assertEquals(200, result.response.status)
        return objectMapper
            .readTree(result.response.contentAsString)
            .path("AccessToken")
            .asText()
    }

    @Test
    fun `apiKey authenticates a request with a valid opaque token`() {
        val apiKey = issueApiToken()
        val builder =
            MockMvcRequestBuilders
                .get("/rest/ping")
                .param("apiKey", apiKey)
                .param("v", "1.16.1")
                .param("c", "test")
        val result = mockMvc.perform(builder).andReturn()
        assertEquals(200, result.response.status)
        assertEquals("ok", xmlRoot(result).getAttribute("status"))
    }

    @Test
    fun `apiKey resolves the calling user for getUser`() {
        val apiKey = issueApiToken()
        val builder =
            MockMvcRequestBuilders
                .get("/rest/getUser")
                .param("apiKey", apiKey)
                .param("f", "json")
                .param("v", "1.16.1")
                .param("c", "test")
        val result = mockMvc.perform(builder).andReturn()
        val body = objectMapper.readTree(result.response.contentAsString).get("subsonic-response")
        assertEquals("ok", body.get("status").asText())
        assertEquals(username, body.get("user").get("username").asText())
    }

    @Test
    fun `invalid apiKey returns Subsonic error code 44`() {
        val builder =
            MockMvcRequestBuilders
                .get("/rest/ping")
                .param("apiKey", "not-a-real-token")
                .param("v", "1.16.1")
                .param("c", "test")
        val result = mockMvc.perform(builder).andReturn()
        assertEquals(200, result.response.status)
        assertEquals(44, xmlErrorCode(result))
    }
}
