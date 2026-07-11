package dev.yaytsa.adapterjellyfin

import dev.yaytsa.application.library.LibraryQueries
import dev.yaytsa.application.ml.port.MlQueryPort
import dev.yaytsa.application.preferences.PreferencesQueries
import dev.yaytsa.application.recommendation.AffinityReranker
import dev.yaytsa.application.recommendation.MusicSurfaceFilter
import dev.yaytsa.application.recommendation.RadioQueueBuilder
import dev.yaytsa.domain.library.Track
import dev.yaytsa.shared.EntityId
import dev.yaytsa.shared.TrackId
import dev.yaytsa.shared.UserId
import org.springframework.stereotype.Component

/**
 * Builds a varied radio station from a single seed track, reusing the same funnel for the empty-queue
 * bootstrap and the client-driven near-end extension so there is exactly one radio algorithm.
 *
 * Pipeline: wide similarity pool ([MlQueryPort.findRadioPool], ef_search-raised) → red-line/audiobook
 * surface filter → [RadioQueueBuilder] structural diversification (1/album, 2/artist) → widen beyond
 * the pool (affinity → taste medoids → favorites → random) when the neighbourhood is too thin to fill
 * the station. The station never silently ships random tracks dressed as radio: [classifyDegraded]
 * reports `no_embedding` (seed unanalyzed) and `sparse_neighbourhood` (analyzed but few real neighbours)
 * so the caller can be honest to the user and the operator.
 */
@Component
class RadioStationService(
    private val mlQuery: MlQueryPort,
    private val libraryQueries: LibraryQueries,
    private val preferencesQueries: PreferencesQueries,
    private val musicSurfaceFilter: MusicSurfaceFilter,
) {
    data class StationTrack(
        val trackId: String,
        val reason: String,
    )

    data class Station(
        val tracks: List<StationTrack>,
        val degraded: String?,
    )

    fun build(
        userId: UserId,
        seedTrackId: EntityId,
        excludeTrackIds: Set<String>,
        targetSize: Int,
    ): Station {
        val exclude = excludeTrackIds + seedTrackId.value
        val pool = mlQuery.findRadioPool(TrackId(seedTrackId.value), RADIO_POOL_SIZE)
        val similar =
            libraryQueries
                .getTracksByIds(pool.map { EntityId(it.value) })
                .let { musicSurfaceFilter.filter(it, userId) }
        val preference = preferenceMaps(userId)
        val ranked =
            AffinityReranker.rerank(
                similar,
                artistAffinity = preference.artist,
                genreAffinity = preference.genre,
                trackAffinity = preference.track,
            )
        val diversified = RadioQueueBuilder.diversify(ranked, targetSize, exclude)

        val ordered = LinkedHashMap<String, String>()
        diversified.forEach { ordered[it.id.value] = REASON_SIMILAR }

        val degraded =
            when {
                pool.isEmpty() -> DEGRADED_NO_EMBEDDING
                distinctAlbums(diversified) < SPARSE_ALBUM_THRESHOLD -> DEGRADED_SPARSE_NEIGHBOURHOOD
                else -> null
            }

        if (ordered.size < targetSize) {
            widen(userId, exclude + ordered.keys, targetSize - ordered.size)
                .forEach { ordered.putIfAbsent(it, REASON_AFFINITY) }
        }

        return Station(ordered.map { StationTrack(it.key, it.value) }, degraded)
    }

    /** Cheap one-query classification used at session start so the very first toast can be honest. */
    fun classifyDegraded(
        userId: UserId,
        seedTrackId: EntityId,
    ): String? {
        val pool = mlQuery.findRadioPool(TrackId(seedTrackId.value), PROBE_POOL_SIZE)
        if (pool.isEmpty()) return DEGRADED_NO_EMBEDDING
        val usable =
            libraryQueries
                .getTracksByIds(pool.map { EntityId(it.value) })
                .let { musicSurfaceFilter.filter(it, userId) }
        return if (distinctAlbums(usable) < SPARSE_ALBUM_THRESHOLD) DEGRADED_SPARSE_NEIGHBOURHOOD else null
    }

    /**
     * Aggregates the user's per-track affinity up to artist and genre level so the discovery pool —
     * mostly tracks never played individually — can still be ranked by taste. Uses only the top
     * affinities already surfaced by [MlQueryPort.getTopAffinities] (one bounded query + one batch
     * track fetch); a loved artist/genre thus promotes its acoustic neighbours, an unengaged one stays
     * neutral. Empty until the user has any listening history.
     */
    private fun preferenceMaps(userId: UserId): PreferenceMaps {
        val affinities = mlQuery.getTopAffinities(userId, AFFINITY_SAMPLE)
        if (affinities.isEmpty()) return PreferenceMaps.EMPTY
        val tracksById =
            libraryQueries
                .getTracksByIds(affinities.map { EntityId(it.trackId.value) })
                .associateBy { it.id.value }
        val artist = HashMap<String, Double>()
        val genre = HashMap<String, Double>()
        val track = HashMap<String, Double>()
        affinities.forEach { affinity ->
            track[affinity.trackId.value] = affinity.affinityScore
            val resolved = tracksById[affinity.trackId.value] ?: return@forEach
            resolved.albumArtistId?.value?.let { artist.merge(it, affinity.affinityScore, Double::plus) }
            resolved.genre?.let { genre.merge(it, affinity.affinityScore, Double::plus) }
        }
        return PreferenceMaps(artist, genre, track)
    }

    private data class PreferenceMaps(
        val artist: Map<String, Double>,
        val genre: Map<String, Double>,
        val track: Map<String, Double>,
    ) {
        companion object {
            val EMPTY = PreferenceMaps(emptyMap(), emptyMap(), emptyMap())
        }
    }

    private fun widen(
        userId: UserId,
        exclude: Set<String>,
        need: Int,
    ): List<String> {
        if (need <= 0) return emptyList()
        val candidates = mutableListOf<Track>()

        val affinityPool = (need * WIDEN_AFFINITY_MULTIPLIER).coerceAtMost(MlQueryPort.MAX_QUERY_LIMIT)
        candidates.addAll(
            libraryQueries.getTracksByIds(
                mlQuery.getTopAffinities(userId, affinityPool).shuffled().map { EntityId(it.trackId.value) },
            ),
        )
        candidates.addAll(
            libraryQueries.getTracksByIds(mlQuery.getTasteClusterRepresentatives(userId).map { EntityId(it.value) }),
        )
        candidates.addAll(
            libraryQueries.getTracksByIds(
                preferencesQueries
                    .find(userId)
                    ?.favorites
                    .orEmpty()
                    .shuffled()
                    .map { EntityId(it.trackId.value) },
            ),
        )
        if (candidates.size < need * 2) {
            candidates.addAll(libraryQueries.browseTracksRandom(need * RANDOM_WIDEN_MULTIPLIER))
        }

        val seen = exclude.toMutableSet()
        return musicSurfaceFilter
            .filter(candidates, userId)
            .asSequence()
            .map { it.id.value }
            .filter { seen.add(it) }
            .take(need)
            .toList()
    }

    private fun distinctAlbums(tracks: List<Track>): Int = tracks.map { it.albumId?.value ?: it.id.value }.toSet().size

    companion object {
        const val DEGRADED_NO_EMBEDDING = "no_embedding"
        const val DEGRADED_SPARSE_NEIGHBOURHOOD = "sparse_neighbourhood"
        const val REASON_SIMILAR = "radio-similar"
        const val REASON_AFFINITY = "radio-affinity"

        private const val RADIO_POOL_SIZE = 200
        private const val PROBE_POOL_SIZE = 60
        private const val AFFINITY_SAMPLE = 500
        private const val SPARSE_ALBUM_THRESHOLD = 3
        private const val WIDEN_AFFINITY_MULTIPLIER = 5
        private const val RANDOM_WIDEN_MULTIPLIER = 4
    }
}
