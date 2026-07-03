package dev.yaytsa.infranotifications.scrobble

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.sun.net.httpserver.HttpServer
import dev.yaytsa.application.library.port.LibraryQueryPort
import dev.yaytsa.domain.library.Album
import dev.yaytsa.domain.library.Artist
import dev.yaytsa.domain.library.Track
import dev.yaytsa.persistence.playback.entity.PlayHistoryEntity
import dev.yaytsa.persistence.playback.jpa.PlayHistoryJpaRepository
import dev.yaytsa.shared.EntityId
import dev.yaytsa.testkit.InMemoryLibraryQueryPort
import io.micrometer.core.instrument.MeterRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.TestPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import java.net.InetSocketAddress
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

@SpringBootTest(classes = [ScrobbleTestApplication::class])
@TestPropertySource(
    properties = [
        "yaytsa.scrobbling.listenbrainz.enabled=true",
        "yaytsa.scrobbling.listenbrainz.token=test-token",
        "yaytsa.scrobbling.listenbrainz.username=user-1",
        "yaytsa.scrobbling.listenbrainz.retry-cooldown-seconds=0",
        "spring.flyway.enabled=true",
        "spring.flyway.locations=classpath:db/playback",
        "spring.flyway.schemas=core_v2_playback",
        "spring.flyway.default-schema=core_v2_playback",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.jpa.properties.hibernate.default_schema=core_v2_playback",
    ],
)
class ListenBrainzScrobbleIntegrationTest {
    @Autowired lateinit var submitter: ListenBrainzScrobbleSubmitter

    @Autowired lateinit var playHistoryJpa: PlayHistoryJpaRepository

    @Autowired lateinit var libraryQueryPort: LibraryQueryPort

    @Autowired lateinit var jdbc: JdbcTemplate

    @Autowired lateinit var meterRegistry: MeterRegistry

    private val library get() = libraryQueryPort as InMemoryLibraryQueryPort

    data class RecordedRequest(
        val method: String,
        val path: String,
        val authorization: String?,
        val body: String,
    )

    companion object {
        private val requests = ConcurrentLinkedQueue<RecordedRequest>()
        private val nextStatus = AtomicInteger(200)

        @JvmStatic
        val listenBrainz: HttpServer =
            HttpServer.create(InetSocketAddress(0), 0).apply {
                createContext("/") { exchange ->
                    val body = exchange.requestBody.readBytes().toString(Charsets.UTF_8)
                    requests.add(
                        RecordedRequest(
                            method = exchange.requestMethod,
                            path = exchange.requestURI.path,
                            authorization = exchange.requestHeaders.getFirst("Authorization"),
                            body = body,
                        ),
                    )
                    val response = """{"status":"ok"}""".toByteArray()
                    exchange.responseHeaders.add("Content-Type", "application/json")
                    exchange.sendResponseHeaders(nextStatus.get(), response.size.toLong())
                    exchange.responseBody.use { it.write(response) }
                }
                start()
            }

        @JvmStatic
        private val postgres: PostgreSQLContainer<*> =
            PostgreSQLContainer("pgvector/pgvector:pg16")
                .withDatabaseName("scrobble_test")
                .withUsername("test")
                .withPassword("test")

        init {
            postgres.start()
        }

        @JvmStatic
        @DynamicPropertySource
        fun props(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { postgres.jdbcUrl }
            registry.add("spring.datasource.username") { postgres.username }
            registry.add("spring.datasource.password") { postgres.password }
            registry.add("yaytsa.scrobbling.listenbrainz.api-url") { "http://localhost:${listenBrainz.address.port}" }
        }
    }

    @BeforeEach
    fun clean() {
        jdbc.execute("TRUNCATE TABLE core_v2_playback.play_history")
        library.tracks.clear()
        library.albums.clear()
        library.artists.clear()
        requests.clear()
        nextStatus.set(200)
    }

    private fun seedLibraryTrack(
        trackName: String = "Paranoid Android",
        artistName: String = "Radiohead",
        albumName: String = "OK Computer",
        durationMs: Long = 387_000,
    ): EntityId {
        val artistId = EntityId(UUID.randomUUID().toString())
        val albumId = EntityId(UUID.randomUUID().toString())
        val trackId = EntityId(UUID.randomUUID().toString())
        library.artists[artistId] =
            Artist(
                id = artistId,
                name = artistName,
                sortName = null,
                parentId = null,
                musicbrainzId = null,
                biography = null,
                coverImagePath = null,
            )
        library.albums[albumId] =
            Album(
                id = albumId,
                name = albumName,
                sortName = null,
                parentId = null,
                artistId = artistId,
                releaseDate = null,
                totalTracks = null,
                totalDiscs = 1,
                coverImagePath = null,
                createdAt = null,
            )
        library.tracks[trackId] =
            Track(
                id = trackId,
                name = trackName,
                sortName = null,
                parentId = albumId,
                albumId = albumId,
                albumArtistId = artistId,
                trackNumber = 1,
                discNumber = 1,
                durationMs = durationMs,
                bitrate = null,
                sampleRate = null,
                channels = null,
                year = null,
                codec = null,
                genre = null,
                coverImagePath = null,
            )
        return trackId
    }

    private fun insertPlay(
        trackId: EntityId,
        startedAt: Instant = Instant.now().minusSeconds(600),
        completed: Boolean = true,
        userId: String = "user-1",
    ): UUID =
        playHistoryJpa
            .save(
                PlayHistoryEntity(
                    userId = userId,
                    itemId = trackId.value,
                    startedAt = startedAt,
                    durationMs = 0,
                    playedMs = 387_000,
                    completed = completed,
                    scrobbled = false,
                    skipped = !completed,
                    recordedAt = Instant.now(),
                ),
            ).id

    private fun counterValue(
        name: String,
        vararg tags: String,
    ): Double = meterRegistry.counter(name, *tags).count()

    @Test
    fun `completed play produces a correct submit-listens call and marks the row scrobbled`() {
        val trackId = seedLibraryTrack()
        val startedAt = Instant.parse("2026-07-01T12:00:00Z")
        val playId = insertPlay(trackId, startedAt)
        val submittedBefore = counterValue("yaytsa.scrobble.submitted", "target", "listenbrainz")

        submitter.poll()

        assertEquals(1, requests.size)
        val request = requests.first()
        assertEquals("POST", request.method)
        assertEquals("/1/submit-listens", request.path)
        assertEquals("Token test-token", request.authorization)
        val json = jacksonObjectMapper().readTree(request.body)
        assertEquals("single", json["listen_type"].asText())
        val listen = json["payload"][0]
        assertEquals(startedAt.epochSecond, listen["listened_at"].asLong())
        val metadata = listen["track_metadata"]
        assertEquals("Radiohead", metadata["artist_name"].asText())
        assertEquals("Paranoid Android", metadata["track_name"].asText())
        assertEquals("OK Computer", metadata["release_name"].asText())
        assertEquals(387_000L, metadata["additional_info"]["duration_ms"].asLong())
        assertTrue(playHistoryJpa.findById(playId).get().scrobbled)
        assertEquals(submittedBefore + 1.0, counterValue("yaytsa.scrobble.submitted", "target", "listenbrainz"))
    }

    @Test
    fun `another user's completed play is never submitted or marked scrobbled`() {
        val trackId = seedLibraryTrack()
        val otherUserPlayId = insertPlay(trackId, userId = "user-2")

        submitter.poll()

        assertEquals(0, requests.size)
        assertFalse(playHistoryJpa.findById(otherUserPlayId).get().scrobbled)
    }

    @Test
    fun `incomplete play is not submitted`() {
        val trackId = seedLibraryTrack()
        val playId = insertPlay(trackId, completed = false)

        submitter.poll()

        assertEquals(0, requests.size)
        assertFalse(playHistoryJpa.findById(playId).get().scrobbled)
    }

    @Test
    fun `multiple pending plays are batched into one import submission`() {
        val firstPlayId = insertPlay(seedLibraryTrack(trackName = "Airbag"))
        val secondPlayId = insertPlay(seedLibraryTrack(trackName = "Let Down"))

        submitter.poll()

        assertEquals(1, requests.size)
        val json = jacksonObjectMapper().readTree(requests.first().body)
        assertEquals("import", json["listen_type"].asText())
        assertEquals(2, json["payload"].size())
        assertTrue(playHistoryJpa.findById(firstPlayId).get().scrobbled)
        assertTrue(playHistoryJpa.findById(secondPlayId).get().scrobbled)
    }

    @Test
    fun `transient failure keeps the listen pending and the next poll retries it`() {
        val playId = insertPlay(seedLibraryTrack())
        nextStatus.set(503)

        submitter.poll()

        assertEquals(1, requests.size)
        assertFalse(playHistoryJpa.findById(playId).get().scrobbled)

        nextStatus.set(200)
        submitter.poll()

        assertEquals(2, requests.size)
        assertTrue(playHistoryJpa.findById(playId).get().scrobbled)
    }

    @Test
    fun `play whose track is missing from the library is dropped without submission`() {
        val playId = insertPlay(EntityId(UUID.randomUUID().toString()))
        val failedBefore = counterValue("yaytsa.scrobble.failed", "target", "listenbrainz", "reason", "unresolvable")

        submitter.poll()

        assertEquals(0, requests.size)
        assertTrue(playHistoryJpa.findById(playId).get().scrobbled)
        assertEquals(failedBefore + 1.0, counterValue("yaytsa.scrobble.failed", "target", "listenbrainz", "reason", "unresolvable"))
    }
}
