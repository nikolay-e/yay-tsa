package dev.yaytsa.application.recommendation

import dev.yaytsa.application.library.LibraryQueries
import dev.yaytsa.application.preferences.PreferencesQueries
import dev.yaytsa.domain.library.Track
import dev.yaytsa.shared.EntityId
import dev.yaytsa.shared.UserId
import dev.yaytsa.testkit.InMemoryLibraryQueryPort
import dev.yaytsa.testkit.InMemoryUserPreferencesRepository
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class MusicSurfaceFilterTest :
    FunSpec({
        fun track(
            id: String,
            durationMs: Long?,
            genre: String? = null,
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
            genre = genre,
            coverImagePath = null,
        )

        val filter =
            MusicSurfaceFilter(
                LibraryQueries(InMemoryLibraryQueryPort()),
                PreferencesQueries(InMemoryUserPreferencesRepository()),
            )

        test("filler tracks are dropped from every music surface, unknown duration is kept") {
            val kept =
                filter.filter(
                    listOf(
                        track("filler", 9_500),
                        track("interlude", 40_000),
                        track("unknown", null),
                        track("audiobook", 3_600_000, genre = "Audiobook"),
                    ),
                    UserId("user-1"),
                )

            kept.map { it.id.value } shouldBe listOf("interlude", "unknown")
        }

        test("isBlocked treats filler like audiobooks") {
            filter.isBlocked(track("filler", 9_500), emptyList()) shouldBe true
            filter.isBlocked(track("song", 180_000), emptyList()) shouldBe false
        }
    })
