package dev.yaytsa.application.recommendation

import dev.yaytsa.application.library.LibraryQueries
import dev.yaytsa.application.ml.port.MlQueryPort
import dev.yaytsa.application.preferences.PreferencesQueries
import dev.yaytsa.domain.library.Track
import dev.yaytsa.shared.EntityId
import dev.yaytsa.shared.UserId

class RecommendationService(
    private val mlQuery: MlQueryPort,
    private val libraryQueries: LibraryQueries,
    private val preferencesQueries: PreferencesQueries,
    private val musicSurfaceFilter: MusicSurfaceFilter,
) {
    /**
     * Shuffled sample of top user-track affinities (so the Daily Mix actually changes between
     * refreshes), then favorites (cold-start for new users), then a varied sample across the
     * library. Result is deduped by trackId.
     */
    fun dailyMixTracks(
        userId: UserId,
        limit: Int,
    ): List<Track> {
        val candidates = collectRecommendationCandidates(userId, limit)
        return musicSurfaceFilter.filter(candidates, userId).take(limit)
    }

    private fun collectRecommendationCandidates(
        userId: UserId,
        limit: Int,
    ): List<Track> {
        val seen = mutableSetOf<String>()
        val out = mutableListOf<Track>()
        // Over-pull so the red-line filter has slack before we take(limit).
        val targetPool = limit * 2

        fun fillFrom(tracks: List<Track>) {
            for (track in tracks) {
                if (out.size >= targetPool) return
                if (seen.add(track.id.value)) out.add(track)
            }
        }

        val affinityPool = (limit * AFFINITY_POOL_MULTIPLIER).coerceAtMost(MlQueryPort.MAX_QUERY_LIMIT)
        fillFrom(tracksInOrder(mlQuery.getTopAffinities(userId, affinityPool).shuffled().map { it.trackId.value }))
        if (out.size < targetPool) fillFrom(tracksInOrder(shuffledFavoriteIds(userId)))
        if (out.size < targetPool) fillFrom(libraryQueries.browseTracksRandom(targetPool - out.size))

        return out
    }

    /**
     * Discovery is the inverse of Daily Mix: instead of resurfacing tracks the user already likes,
     * it surfaces tracks *adjacent* to their taste that they have not heard. We build a "heard" set
     * (top affinities + favorites), seed kNN search from the user's taste-cluster medoids (the real
     * facets of their taste), collect similar tracks, drop anything in the heard set, then apply the
     * same red-line + audiobook filtering as Daily Mix. Cold start falls back to a random library
     * sample minus heard tracks. Shuffled so each refresh surfaces a fresh slice.
     */
    fun discoveryTracks(
        userId: UserId,
        limit: Int,
    ): List<Track> {
        val heard = collectHeardTrackIds(userId, limit)
        val candidates = collectDiscoveryCandidates(userId, limit, heard)
        return musicSurfaceFilter
            .filter(candidates, userId)
            .shuffled()
            .take(limit)
    }

    private fun collectHeardTrackIds(
        userId: UserId,
        limit: Int,
    ): Set<String> {
        val heardPool = (limit * HEARD_POOL_MULTIPLIER).coerceAtMost(MlQueryPort.MAX_QUERY_LIMIT)
        val heard = mutableSetOf<String>()
        mlQuery.getTopAffinities(userId, heardPool).forEach { heard.add(it.trackId.value) }
        preferencesQueries
            .find(userId)
            ?.favorites
            .orEmpty()
            .forEach { heard.add(it.trackId.value) }
        return heard
    }

    private fun collectDiscoveryCandidates(
        userId: UserId,
        limit: Int,
        heard: Set<String>,
    ): List<Track> {
        val seen = mutableSetOf<String>()
        val out = mutableListOf<Track>()
        val targetPool = limit * 2

        val seeds =
            mlQuery
                .getTasteClusterRepresentatives(userId)
                .ifEmpty {
                    mlQuery.getTopAffinities(userId, DISCOVERY_SEED_FALLBACK_POOL).map { it.trackId }
                }

        for (seed in seeds) {
            if (out.size >= targetPool) break
            val candidateIds =
                mlQuery
                    .findSimilarTracks(seed, DISCOVERY_SIMILARITY_K)
                    .map { it.value }
                    .filter { it !in heard && it !in seen }
            for (track in tracksInOrder(candidateIds)) {
                if (out.size >= targetPool) break
                if (seen.add(track.id.value)) out.add(track)
            }
        }

        if (out.size < targetPool) {
            libraryQueries
                .browseTracksRandom((targetPool - out.size) * 2)
                .asSequence()
                .takeWhile { out.size < targetPool }
                .filter { it.id.value !in heard && seen.add(it.id.value) }
                .forEach { out.add(it) }
        }

        return out
    }

    /**
     * Radio seeds = one station per artist. Primary source is the representative track (medoid) of
     * each of the user's taste clusters — one seed per real taste facet, so radio spans the whole
     * taste instead of a single averaged centroid. Falls through to a wide affinity pool, then
     * favorites and a random library sample so the row isn't empty for cold-start users.
     */
    fun radioSeedTracks(
        userId: UserId,
        limit: Int,
    ): List<Track> {
        val redLineTerms = musicSurfaceFilter.loadRedLineTerms(userId)
        val perArtist = LinkedHashMap<String, Track>()
        val seenTracks = mutableSetOf<String>()

        collectSeedsPerArtist(perArtist, seenTracks, redLineTerms, tracksInOrder(mlQuery.getTasteClusterRepresentatives(userId).map { it.value }))
        if (perArtist.size < limit) {
            val poolSize = (limit * SEED_POOL_MULTIPLIER).coerceAtMost(MlQueryPort.MAX_QUERY_LIMIT)
            val affinityIds = mlQuery.getTopAffinities(userId, poolSize).map { it.trackId.value }
            collectSeedsPerArtist(perArtist, seenTracks, redLineTerms, tracksInOrder(affinityIds))
        }

        val picked = perArtist.values.toMutableList().also { it.shuffle() }
        if (picked.size < limit) {
            appendSeedsForFreshArtists(picked, perArtist, seenTracks, redLineTerms, tracksInOrder(shuffledFavoriteIds(userId)), limit)
        }
        if (picked.size < limit) {
            val randomDraw = libraryQueries.browseTracksRandom((limit - picked.size) * 2)
            appendSeedsForFreshArtists(picked, perArtist, seenTracks, redLineTerms, randomDraw, limit)
        }

        return picked.take(limit)
    }

    private fun collectSeedsPerArtist(
        perArtist: LinkedHashMap<String, Track>,
        seenTracks: MutableSet<String>,
        redLineTerms: List<String>,
        tracks: List<Track>,
    ) {
        tracks.forEach { track ->
            if (musicSurfaceFilter.isBlocked(track, redLineTerms)) return@forEach
            val artistKey = track.albumArtistId?.value ?: track.id.value
            if (artistKey !in perArtist) perArtist[artistKey] = track
            seenTracks.add(track.id.value)
        }
    }

    private fun appendSeedsForFreshArtists(
        picked: MutableList<Track>,
        perArtist: LinkedHashMap<String, Track>,
        seenTracks: MutableSet<String>,
        redLineTerms: List<String>,
        tracks: List<Track>,
        limit: Int,
    ) {
        tracks
            .asSequence()
            .takeWhile { picked.size < limit }
            .filterNot { musicSurfaceFilter.isBlocked(it, redLineTerms) }
            .forEach { track ->
                val artistKey = track.albumArtistId?.value ?: track.id.value
                if (perArtist.put(artistKey, track) == null && seenTracks.add(track.id.value)) {
                    picked.add(track)
                }
            }
    }

    private fun shuffledFavoriteIds(userId: UserId): List<String> =
        preferencesQueries
            .find(userId)
            ?.favorites
            .orEmpty()
            .shuffled()
            .map { it.trackId.value }

    private fun tracksInOrder(trackIds: List<String>): List<Track> {
        if (trackIds.isEmpty()) return emptyList()
        val byId = libraryQueries.getTracksByIds(trackIds.map { EntityId(it) }).associateBy { it.id.value }
        return trackIds.mapNotNull { byId[it] }
    }

    companion object {
        // Larger pool than [limit] so .shuffled() gives the user a fresh slice on each refresh
        // instead of the deterministic top-N by affinity_score.
        private const val AFFINITY_POOL_MULTIPLIER = 5

        // Radio seeds collapse to one-per-artist, so we need a wider pool than [limit] to find
        // [limit] distinct artists even when a user's affinities cluster around a few favorites.
        private const val SEED_POOL_MULTIPLIER = 20

        // Discovery excludes everything the user already knows. Pull a deep affinity history so the
        // "heard" set is wide enough that kNN neighbours are genuinely new, not recently-played.
        private const val HEARD_POOL_MULTIPLIER = 20

        // When the user has no taste clusters yet, seed discovery from their strongest affinities.
        private const val DISCOVERY_SEED_FALLBACK_POOL = 25

        // Neighbours pulled per seed; over-pulled so the heard/red-line/audiobook filters have slack.
        private const val DISCOVERY_SIMILARITY_K = 25
    }
}
