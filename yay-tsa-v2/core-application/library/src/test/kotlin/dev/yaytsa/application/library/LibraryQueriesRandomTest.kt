package dev.yaytsa.application.library

import dev.yaytsa.domain.library.Track
import dev.yaytsa.shared.EntityId
import dev.yaytsa.testkit.InMemoryLibraryQueryPort
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe

class LibraryQueriesRandomTest :
    FunSpec({
        fun track(
            id: String,
            durationMs: Long?,
        ) = Track(
            id = EntityId(id),
            name = "Track $id",
            sortName = null,
            parentId = null,
            albumId = null,
            albumArtistId = null,
            trackNumber = null,
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

        test("random browse drops CD-filler tracks shorter than the minimum duration") {
            val port = InMemoryLibraryQueryPort()
            repeat(20) { port.tracks[EntityId("filler-$it")] = track("filler-$it", 9_500) }
            repeat(20) { port.tracks[EntityId("song-$it")] = track("song-$it", 180_000) }
            val queries = LibraryQueries(port)

            val drawn = queries.browseTracksRandom(10)

            drawn.shouldNotBeEmpty()
            drawn.count { it.id.value.startsWith("filler-") } shouldBe 0
        }

        test("tracks with unknown duration are kept") {
            val port = InMemoryLibraryQueryPort()
            port.tracks[EntityId("unknown")] = track("unknown", null)
            val queries = LibraryQueries(port)

            queries.browseTracksRandom(5).map { it.id.value } shouldBe listOf("unknown")
        }
    })
