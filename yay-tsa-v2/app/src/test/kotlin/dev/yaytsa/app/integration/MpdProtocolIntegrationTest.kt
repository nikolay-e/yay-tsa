package dev.yaytsa.app.integration

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.Closeable
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.UUID

// MPD runs in the shared context (enabled on mpdPort in HttpIntegrationTestBase), so this connects
// over TCP without a second Spring context — avoiding the singleton JCache CacheManager collision.
class MpdProtocolIntegrationTest : HttpIntegrationTestBase() {
    @Autowired
    lateinit var jdbc: JdbcTemplate

    private lateinit var artistId: UUID
    private lateinit var albumId: UUID
    private lateinit var trackId: UUID
    private lateinit var artistName: String
    private lateinit var albumName: String
    private lateinit var trackTitle: String

    @BeforeEach
    fun seedLibrary() {
        val suffix = UUID.randomUUID().toString().take(8)
        artistId = UUID.randomUUID()
        albumId = UUID.randomUUID()
        trackId = UUID.randomUUID()
        artistName = "Mpd Artist $suffix"
        albumName = "Mpd Album $suffix"
        trackTitle = "Mpd Track $suffix"
        insertEntity(artistId, "ARTIST", artistName)
        jdbc.update("INSERT INTO core_v2_library.artists (entity_id) VALUES (?)", artistId)
        insertEntity(albumId, "ALBUM", albumName)
        jdbc.update("INSERT INTO core_v2_library.albums (entity_id, artist_id) VALUES (?,?)", albumId, artistId)
        insertEntity(trackId, "TRACK", trackTitle)
        jdbc.update(
            "INSERT INTO core_v2_library.audio_tracks " +
                "(entity_id, album_id, album_artist_id, track_number, disc_number, duration_ms, year) " +
                "VALUES (?,?,?,?,?,?,?)",
            trackId,
            albumId,
            artistId,
            7,
            1,
            180_000L,
            2021,
        )
    }

    private fun insertEntity(
        id: UUID,
        type: String,
        name: String,
    ) {
        jdbc.update(
            "INSERT INTO core_v2_library.entities (id, entity_type, name, sort_name, search_text) VALUES (?,?,?,?,?)",
            id,
            type,
            name,
            name.lowercase(),
            name.lowercase(),
        )
    }

    private class MpdClient(
        port: Int,
    ) : Closeable {
        private val socket = Socket("127.0.0.1", port).apply { soTimeout = 10_000 }
        private val reader = BufferedReader(InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))
        private val writer = BufferedWriter(OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8))

        init {
            reader.readLine()
        }

        fun send(line: String) {
            writer.write(line)
            writer.write("\n")
            writer.flush()
        }

        fun readResponse(): String {
            val sb = StringBuilder()
            while (true) {
                val line = reader.readLine() ?: break
                sb.append(line).append('\n')
                if (line == "OK" || line.startsWith("ACK")) break
            }
            return sb.toString()
        }

        fun command(line: String): String {
            send(line)
            return readResponse()
        }

        override fun close() = socket.close()
    }

    private fun runCommands(lines: List<String>): String =
        MpdClient(mpdPort).use { client ->
            lines.forEach { client.send(it) }
            client.readResponse()
        }

    private fun statusValue(
        status: String,
        key: String,
    ): String? =
        status
            .lines()
            .firstOrNull { it.startsWith("$key: ") }
            ?.removePrefix("$key: ")

    private fun seedQueueAndPlay(client: MpdClient) {
        assertEquals("OK\n", client.command("clear"))
        assertEquals("OK\n", client.command("add $trackId"))
        assertEquals("OK\n", client.command("play 0"))
    }

    @Test
    fun `command_list_ok_begin emits a list_OK after each sub-command and one terminating OK`() {
        val response = runCommands(listOf("command_list_ok_begin", "status", "status", "command_list_end"))

        val listOkCount = response.lines().count { it == "list_OK" }
        assertTrue(listOkCount == 2, "expected one list_OK per sub-command, got $listOkCount in:\n$response")
        assertTrue(response.trimEnd().endsWith("OK"), "batch must end with a single terminating OK")
        assertFalse(
            response.trim() == "OK",
            "a successful batch must carry each sub-command's payload, not a bare OK (the pre-fix bug)",
        )
    }

    @Test
    fun `plain command_list_begin concatenates payloads with no list_OK separators`() {
        val response = runCommands(listOf("command_list_begin", "status", "command_list_end"))
        assertFalse(response.lines().any { it == "list_OK" }, "plain command list must not emit list_OK")
        assertTrue(response.trimEnd().endsWith("OK"))
        assertTrue(response.contains("state:") || response.contains("volume:"), "status payload must be present, was:\n$response")
    }

    @Test
    fun `command list ACK carries the failing command's offset`() {
        val response = runCommands(listOf("command_list_ok_begin", "ping", "bogus", "command_list_end"))
        assertTrue(response.contains("ACK [5@1] {bogus}"), "ACK must carry offset 1 for the second command, was:\n$response")
    }

    @Test
    fun `status during playback reports advancing elapsed and track duration`() {
        MpdClient(mpdPort).use { client ->
            seedQueueAndPlay(client)
            val first = client.command("status")
            assertEquals("play", statusValue(first, "state"), "expected playing state, was:\n$first")
            assertEquals("180.000", statusValue(first, "duration"), "status must report track duration, was:\n$first")
            val firstElapsed = statusValue(first, "elapsed")?.toDouble() ?: error("no elapsed line in:\n$first")
            Thread.sleep(1200)
            val second = client.command("status")
            val secondElapsed = statusValue(second, "elapsed")?.toDouble() ?: error("no elapsed line in:\n$second")
            assertTrue(
                secondElapsed > firstElapsed + 0.5,
                "elapsed must advance during playback: $firstElapsed -> $secondElapsed",
            )
            val timeLine = statusValue(second, "time")
            assertTrue(timeLine?.endsWith(":180") == true, "time must carry total seconds, was: $timeLine")
        }
    }

    @Test
    fun `currentsong and playlistinfo emit name tags instead of UUIDs`() {
        MpdClient(mpdPort).use { client ->
            seedQueueAndPlay(client)
            listOf(client.command("currentsong"), client.command("playlistinfo")).forEach { response ->
                assertTrue(response.contains("Artist: $artistName"), "missing Artist name in:\n$response")
                assertTrue(response.contains("AlbumArtist: $artistName"), "missing AlbumArtist name in:\n$response")
                assertTrue(response.contains("Album: $albumName"), "missing Album name in:\n$response")
                assertTrue(response.contains("Title: $trackTitle"), "missing Title in:\n$response")
                assertTrue(response.contains("Track: 7"), "missing Track number in:\n$response")
                assertTrue(response.contains("Date: 2021"), "missing Date in:\n$response")
                assertFalse(response.contains("AlbumArtistId:"), "UUID tag lines must be gone:\n$response")
                assertFalse(response.contains(artistId.toString()), "artist UUID must not leak into tags:\n$response")
            }
        }
    }

    @Test
    fun `playlist version increments after add`() {
        MpdClient(mpdPort).use { client ->
            assertEquals("OK\n", client.command("clear"))
            val before = statusValue(client.command("status"), "playlist")?.toInt() ?: error("no playlist version")
            assertEquals("OK\n", client.command("add $trackId"))
            val after = statusValue(client.command("status"), "playlist")?.toInt() ?: error("no playlist version")
            assertTrue(after > before, "playlist version must bump on add: $before -> $after")
        }
    }

    @Test
    fun `plchanges returns full queue for stale version and nothing when current`() {
        MpdClient(mpdPort).use { client ->
            assertEquals("OK\n", client.command("clear"))
            assertEquals("OK\n", client.command("add $trackId"))
            val current = statusValue(client.command("status"), "playlist")?.toInt() ?: error("no playlist version")
            val stale = client.command("plchanges 0")
            assertTrue(stale.contains("file: $trackId"), "stale version must get full queue, was:\n$stale")
            assertTrue(stale.contains("Title: $trackTitle"))
            assertEquals("OK\n", client.command("plchanges $current"), "up-to-date version must get no entries")
        }
    }

    @Test
    fun `idle wakes with changed playlist after a queue mutation`() {
        MpdClient(mpdPort).use { mutator ->
            assertEquals("OK\n", mutator.command("clear"))
            MpdClient(mpdPort).use { idler ->
                idler.send("idle")
                Thread.sleep(600)
                assertEquals("OK\n", mutator.command("add $trackId"))
                val woken = idler.readResponse()
                assertTrue(woken.contains("changed: playlist"), "idle must report the playlist subsystem, was:\n$woken")
                assertFalse(woken.contains("changed: player"), "queue-only change must not report player, was:\n$woken")
            }
        }
    }

    @Test
    fun `lsinfo descends artist to album to songs`() {
        MpdClient(mpdPort).use { client ->
            val root = client.command("lsinfo")
            assertTrue(root.contains("directory: $artistName"), "root must list artist directories, was:\n$root")
            val albums = client.command("lsinfo \"$artistName\"")
            assertTrue(albums.contains("directory: $artistName/$albumName"), "artist must list album directories, was:\n$albums")
            val songs = client.command("lsinfo \"$artistName/$albumName\"")
            assertTrue(songs.contains("file: $trackId"), "album must list songs, was:\n$songs")
            assertTrue(songs.contains("Title: $trackTitle"), "songs must carry full tag blocks, was:\n$songs")
            assertTrue(songs.contains("Artist: $artistName"), "songs must carry artist names, was:\n$songs")
        }
    }

    @Test
    fun `add resolves hierarchical album path into queue entries`() {
        MpdClient(mpdPort).use { client ->
            assertEquals("OK\n", client.command("clear"))
            assertEquals("OK\n", client.command("add \"$artistName/$albumName\""))
            val playlist = client.command("playlistinfo")
            assertTrue(playlist.contains("file: $trackId"), "album path add must enqueue its tracks, was:\n$playlist")
        }
    }
}
