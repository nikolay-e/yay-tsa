package dev.yaytsa.application.recommendation

import dev.yaytsa.domain.library.Track

/**
 * Turns a similarity-ordered candidate pool into a *varied* radio station.
 *
 * The album-lock the user reported is structural: the nearest neighbours of a seed in timbre space
 * are overwhelmingly its own album (shared mastering/production), so a naive `take(n)` over kNN
 * returns ten tracks from one album. The fix is not a better embedding — it is de-clustering the
 * pool with hard structural caps (at most one track per album, two per artist) while walking the
 * pool in similarity order so the station still *starts on the seed's closest match* and drifts
 * outward.
 *
 * Pure and deterministic: relevance is encoded entirely by the input order (most similar first),
 * so this is a function of (pool, caps, exclusions) with no I/O, no clock, no scores to invent.
 *
 * Widen-don't-stop: radio is an endless medium, so when strict caps cannot fill [targetSize] from
 * the pool (small library / thin neighbourhood) the caps are progressively relaxed across passes
 * rather than padding with noise or stopping short — coherence degrades gracefully, the music does
 * not run dry. (Callers widen *beyond* the pool — affinity, taste medoids — when even the relaxed
 * passes fall short.)
 */
object RadioQueueBuilder {
    fun diversify(
        orderedCandidates: List<Track>,
        targetSize: Int,
        excludeTrackIds: Set<String> = emptySet(),
        perArtistCap: Int = DEFAULT_PER_ARTIST_CAP,
        perAlbumCap: Int = DEFAULT_PER_ALBUM_CAP,
    ): List<Track> {
        if (targetSize <= 0) return emptyList()

        val chosen = LinkedHashMap<String, Track>()
        val artistCount = HashMap<String, Int>()
        val albumCount = HashMap<String, Int>()

        // Pass 1 enforces the strict variety floor (1/album, 2/artist) over the most-similar tracks.
        // Later passes only run when the pool could not fill the station under strict caps, relaxing
        // artist first (a deep single-artist seed is more coherent than dead air), then album.
        val passes =
            listOf(
                perArtistCap to perAlbumCap,
                (perArtistCap * 2) to perAlbumCap,
                Int.MAX_VALUE to (perAlbumCap + 1),
                Int.MAX_VALUE to Int.MAX_VALUE,
            )

        for ((artistCap, albumCap) in passes) {
            if (chosen.size >= targetSize) break
            for (track in orderedCandidates) {
                if (chosen.size >= targetSize) break
                val trackId = track.id.value
                if (trackId in excludeTrackIds || trackId in chosen) continue
                val artistKey = track.albumArtistId?.value ?: "artist:$trackId"
                val albumKey = track.albumId?.value ?: "album:$trackId"
                if ((artistCount[artistKey] ?: 0) >= artistCap) continue
                if ((albumCount[albumKey] ?: 0) >= albumCap) continue
                chosen[trackId] = track
                artistCount[artistKey] = (artistCount[artistKey] ?: 0) + 1
                albumCount[albumKey] = (albumCount[albumKey] ?: 0) + 1
            }
        }

        return chosen.values.toList()
    }

    const val DEFAULT_PER_ARTIST_CAP = 2
    const val DEFAULT_PER_ALBUM_CAP = 1
}
