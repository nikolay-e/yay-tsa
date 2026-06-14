package dev.yaytsa.worker.metadata

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

class ReleaseMatcherTest :
    FunSpec({
        fun candidate(
            mbid: String,
            title: String,
            artist: String?,
            score: Int = 100,
            trackCount: Int? = null,
        ) = MetadataCandidate(mbid = mbid, title = title, artistName = artist, score = score, trackCount = trackCount)

        test("normalize strips accents, articles, parentheticals and casing") {
            ReleaseMatcher.normalize("The Café (Deluxe Edition)") shouldBe "cafe"
            ReleaseMatcher.normalize("Sigur Rós & Friends") shouldBe "sigurrosandfriends"
            ReleaseMatcher.normalize("Song feat. Someone") shouldBe "song"
        }

        test("levenshtein distance is symmetric and exact") {
            ReleaseMatcher.levenshtein("kitten", "sitting") shouldBe 3
            ReleaseMatcher.levenshtein("abc", "abc") shouldBe 0
            ReleaseMatcher.levenshtein("", "abc") shouldBe 3
        }

        test("normalizedEditDistance of identical-after-normalization strings is zero") {
            ReleaseMatcher.normalizedEditDistance("The Wall", "Wall") shouldBe 0.0
        }

        test("accepts a clear winner within distance threshold and gap") {
            val local = LocalAlbum(title = "Discovery", artistName = "Daft Punk", trackCount = 14, year = 2001)
            val candidates =
                listOf(
                    candidate("mbid-1", "Discovery", "Daft Punk", trackCount = 14),
                    candidate("mbid-2", "Homework", "Daft Punk", trackCount = 16),
                )
            val match = ReleaseMatcher.match(local, candidates)
            match?.candidate?.mbid shouldBe "mbid-1"
        }

        test("rejects when no candidate is close enough") {
            val local = LocalAlbum(title = "Discovery", artistName = "Daft Punk", trackCount = 14, year = 2001)
            val candidates =
                listOf(
                    candidate("mbid-1", "Random Access Memories", "Daft Punk"),
                    candidate("mbid-2", "Homework", "Daft Punk"),
                )
            ReleaseMatcher.match(local, candidates).shouldBeNull()
        }

        test("rejects ambiguous match when top two are within the gap") {
            val local = LocalAlbum(title = "Greatest Hits", artistName = "Various", trackCount = null, year = null)
            val candidates =
                listOf(
                    candidate("mbid-1", "Greatest Hits", "Artist A"),
                    candidate("mbid-2", "Greatest Hits", "Artist B"),
                )
            ReleaseMatcher.match(local, candidates).shouldBeNull()
        }

        test("various-artists local name skips artist scoring") {
            ReleaseMatcher.isVariousArtists("Various Artists") shouldBe true
            ReleaseMatcher.isVariousArtists("VA") shouldBe true
            ReleaseMatcher.isVariousArtists("Daft Punk") shouldBe false

            val local = LocalAlbum(title = "Now That's Music", artistName = "Various", trackCount = 20, year = 2000)
            val candidates =
                listOf(
                    candidate("mbid-1", "Now That's Music", "Various Composers", trackCount = 20),
                    candidate("mbid-2", "Completely Different Title", "Whoever", trackCount = 8),
                )
            ReleaseMatcher.match(local, candidates)?.candidate?.mbid shouldBe "mbid-1"
        }

        test("empty candidate list yields no match") {
            val local = LocalAlbum(title = "Anything", artistName = "Anyone", trackCount = 1, year = 2020)
            ReleaseMatcher.match(local, emptyList()).shouldBeNull()
        }
    })
