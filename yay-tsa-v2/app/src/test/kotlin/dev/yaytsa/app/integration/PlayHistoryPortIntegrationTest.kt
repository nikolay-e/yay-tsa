package dev.yaytsa.app.integration

import dev.yaytsa.application.playback.ListeningStatsGroupBy
import dev.yaytsa.application.playback.ListeningStatsService
import dev.yaytsa.application.playback.port.PlayHistoryQueryPort
import dev.yaytsa.application.playback.port.PlayHistoryWritePort
import dev.yaytsa.shared.TrackId
import dev.yaytsa.shared.UserId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class PlayHistoryPortIntegrationTest : HttpIntegrationTestBase() {
    @Autowired
    lateinit var writePort: PlayHistoryWritePort

    @Autowired
    lateinit var queryPort: PlayHistoryQueryPort

    @Autowired
    lateinit var listeningStatsService: ListeningStatsService

    @Autowired
    lateinit var jdbc: JdbcTemplate

    private val base: Instant = Instant.parse("2026-07-01T10:00:00Z")

    private fun record(
        uid: UserId,
        trackId: String,
        startedAt: Instant,
        completed: Boolean,
        skipped: Boolean,
        source: String?,
        deviceId: String?,
    ) = writePort.record(
        userId = uid,
        trackId = TrackId(trackId),
        startedAt = startedAt,
        durationMs = 100_000,
        playedMs = if (completed) 90_000 else 20_000,
        completed = completed,
        skipped = skipped,
        source = source,
        deviceId = deviceId,
    )

    @Test
    fun `recorded events round-trip through window page and count queries`() {
        val uid = UserId(UUID.randomUUID().toString())
        val adaptiveTrack = UUID.randomUUID().toString()
        val subsonicTrack = UUID.randomUUID().toString()
        val untaggedTrack = UUID.randomUUID().toString()
        record(uid, adaptiveTrack, base, completed = true, skipped = false, source = "adaptive", deviceId = "device-a")
        record(uid, subsonicTrack, base.plusSeconds(3600), completed = false, skipped = true, source = "subsonic", deviceId = null)
        record(uid, untaggedTrack, base.plusSeconds(7200), completed = false, skipped = false, source = null, deviceId = "device-b")

        val window = queryPort.eventsInWindow(uid, base, base.plusSeconds(10_800))
        assertEquals(3, window.size)
        val adaptive = window.first { it.trackId.value == adaptiveTrack }
        assertEquals(100_000L, adaptive.durationMs)
        assertEquals(90_000L, adaptive.playedMs)
        assertEquals(true, adaptive.completed)
        assertEquals("adaptive", adaptive.source)
        assertEquals("device-a", adaptive.deviceId)

        val midWindow = queryPort.eventsInWindow(uid, base.plusSeconds(1800), base.plusSeconds(5400))
        assertEquals(listOf(subsonicTrack), midWindow.map { it.trackId.value }, "window bounds must be [since, until)")

        val firstPage = queryPort.historyPage(uid, null, null, null, limit = 2, offset = 0)
        assertEquals(
            listOf(untaggedTrack, subsonicTrack),
            firstPage.map { it.trackId.value },
            "history pages newest-first",
        )
        val secondPage = queryPort.historyPage(uid, null, null, null, limit = 2, offset = 2)
        assertEquals(listOf(adaptiveTrack), secondPage.map { it.trackId.value })

        val adaptiveOnly = queryPort.historyPage(uid, null, null, "adaptive", limit = 10, offset = 0)
        assertEquals(listOf(adaptiveTrack), adaptiveOnly.map { it.trackId.value })

        val sinceOnly = queryPort.historyPage(uid, base.plusSeconds(1800), null, null, limit = 10, offset = 0)
        assertEquals(2, sinceOnly.size)

        assertEquals(3, queryPort.historyCount(uid, null, null, null))
        assertEquals(1, queryPort.historyCount(uid, null, null, "subsonic"))
        assertEquals(2, queryPort.historyCount(uid, base.plusSeconds(1800), null, null))
        assertEquals(0, queryPort.historyCount(UserId(UUID.randomUUID().toString()), null, null, null), "user scoping must hold")
    }

    @Test
    fun `listening stats resolve artist and genre groups through the library`() {
        val uid = UserId(UUID.randomUUID().toString())
        val artistId = seedEntity("ARTIST", "Statsy Artist")
        val genreId = seedGenre("Statscore")
        val trackA = seedTrackEntity("Stats Track A", artistId, genreId)
        val trackB = seedTrackEntity("Stats Track B", artistId, genreId)
        val ghostTrack = UUID.randomUUID().toString()
        record(uid, trackA, base, completed = true, skipped = false, source = null, deviceId = null)
        record(uid, trackB, base.plusSeconds(600), completed = false, skipped = true, source = null, deviceId = null)
        record(uid, ghostTrack, base.plusSeconds(1200), completed = true, skipped = false, source = null, deviceId = null)

        val byArtist =
            listeningStatsService.stats(uid, base, base.plusSeconds(3600), ListeningStatsGroupBy.ARTIST, ZoneOffset.UTC)
        assertEquals(3, byArtist.totalEvents)
        val artistRow = byArtist.rows.first { it.group == "Statsy Artist" }
        assertEquals(2, artistRow.plays)
        assertEquals(1, artistRow.completions)
        assertEquals(1, artistRow.skips)
        assertTrue(artistRow.lowSupport, "n=2 must carry the low-support flag")
        assertEquals(1, byArtist.rows.first { it.group == ListeningStatsService.UNKNOWN_GROUP }.plays)

        val byGenre =
            listeningStatsService.stats(uid, base, base.plusSeconds(3600), ListeningStatsGroupBy.GENRE, ZoneOffset.UTC)
        assertEquals(2, byGenre.rows.first { it.group == "Statscore" }.plays)
    }

    private fun seedEntity(
        entityType: String,
        name: String,
    ): String {
        val id = UUID.randomUUID()
        jdbc.update(
            "INSERT INTO core_v2_library.entities (id, entity_type, name, sort_name, source_path, search_text) VALUES (?,?,?,?,?,?)",
            id,
            entityType,
            name,
            name.lowercase(),
            "/stats/$id",
            name.lowercase(),
        )
        if (entityType == "ARTIST") {
            jdbc.update("INSERT INTO core_v2_library.artists (entity_id) VALUES (?)", id)
        }
        return id.toString()
    }

    private fun seedGenre(name: String): String {
        val id = UUID.randomUUID()
        jdbc.update("INSERT INTO core_v2_library.genres (id, name) VALUES (?,?) ON CONFLICT (name) DO NOTHING", id, name)
        return jdbc.queryForObject("SELECT id::text FROM core_v2_library.genres WHERE name = ?", String::class.java, name)!!
    }

    private fun seedTrackEntity(
        name: String,
        artistId: String,
        genreId: String,
    ): String {
        val id = UUID.fromString(seedEntity("TRACK", name))
        jdbc.update(
            "INSERT INTO core_v2_library.audio_tracks (entity_id, duration_ms, album_artist_id) VALUES (?,?,?)",
            id,
            100_000L,
            UUID.fromString(artistId),
        )
        jdbc.update(
            "INSERT INTO core_v2_library.entity_genres (entity_id, genre_id) VALUES (?,?)",
            id,
            UUID.fromString(genreId),
        )
        return id.toString()
    }
}
