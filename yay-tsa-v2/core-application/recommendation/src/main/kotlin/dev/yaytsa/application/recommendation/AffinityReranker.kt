package dev.yaytsa.application.recommendation

import dev.yaytsa.domain.library.Track
import kotlin.math.abs

/**
 * Re-orders a similarity-ordered radio pool by the user's *predicted* preference, so novelty is
 * ranked by taste rather than by pure timbre-distance to a single seed — without deleting novelty.
 * The pool is unchanged and downstream [RadioQueueBuilder] diversification still runs; only the walk
 * order is nudged, and by a bounded amount, so a track must be *both* acoustically near the seed
 * (in the pool) *and* preferred to rise.
 *
 * The signal is affinity projected to artist and genre level, because a discovery pool is dominated
 * by tracks the user has never played individually (per-track affinity absent), yet their artist or
 * genre almost always carries a history signal. A loved artist/genre pulls a candidate earlier; a
 * disliked one pushes it later. Preference is normalized per map (scale-free, no hand-tuned
 * thresholds) and clamped so the strongest taste signal moves a track at most [maxNudge] positions.
 *
 * Pure and deterministic: a function of (pool order, preference maps). No I/O, no clock.
 */
object AffinityReranker {
    fun rerank(
        orderedCandidates: List<Track>,
        artistAffinity: Map<String, Double>,
        genreAffinity: Map<String, Double>,
        trackAffinity: Map<String, Double> = emptyMap(),
        maxNudge: Int = DEFAULT_MAX_NUDGE,
    ): List<Track> {
        if (orderedCandidates.size < 2) return orderedCandidates
        if (artistAffinity.isEmpty() && genreAffinity.isEmpty() && trackAffinity.isEmpty()) return orderedCandidates

        val artistScale = maxAbs(artistAffinity)
        val genreScale = maxAbs(genreAffinity)
        val trackScale = maxAbs(trackAffinity)

        return orderedCandidates
            .withIndex()
            .sortedBy { (index, track) ->
                val pref = preference(track, artistAffinity, genreAffinity, trackAffinity, artistScale, genreScale, trackScale)
                index - maxNudge * pref
            }.map { it.value }
    }

    private fun preference(
        track: Track,
        artistAffinity: Map<String, Double>,
        genreAffinity: Map<String, Double>,
        trackAffinity: Map<String, Double>,
        artistScale: Double,
        genreScale: Double,
        trackScale: Double,
    ): Double {
        val artist = normalize(track.albumArtistId?.value?.let { artistAffinity[it] }, artistScale)
        val genre = normalize(track.genre?.let { genreAffinity[it] }, genreScale)
        val exact = normalize(trackAffinity[track.id.value], trackScale)
        return (ARTIST_WEIGHT * artist + GENRE_WEIGHT * genre + TRACK_WEIGHT * exact).coerceIn(-1.0, 1.0)
    }

    private fun normalize(
        value: Double?,
        scale: Double,
    ): Double = if (value == null || scale <= 0.0) 0.0 else (value / scale).coerceIn(-1.0, 1.0)

    private fun maxAbs(affinity: Map<String, Double>): Double = affinity.values.maxOfOrNull { abs(it) } ?: 0.0

    const val DEFAULT_MAX_NUDGE = 40
    private const val ARTIST_WEIGHT = 0.6
    private const val GENRE_WEIGHT = 0.25
    private const val TRACK_WEIGHT = 0.15
}
