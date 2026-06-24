package dev.yaytsa.application.ml.port

import dev.yaytsa.domain.ml.TasteProfile
import dev.yaytsa.domain.ml.TrackFeatures
import dev.yaytsa.domain.ml.UserTrackAffinity
import dev.yaytsa.shared.TrackId
import dev.yaytsa.shared.UserId

interface MlQueryPort {
    companion object {
        const val MAX_QUERY_LIMIT = 1000
    }

    fun getTrackFeatures(trackId: TrackId): TrackFeatures?

    fun getTasteProfile(userId: UserId): TasteProfile?

    fun getUserTrackAffinity(
        userId: UserId,
        trackId: TrackId,
    ): UserTrackAffinity?

    /** @param limit capped at [MAX_QUERY_LIMIT] by implementations */
    fun getTopAffinities(
        userId: UserId,
        limit: Int,
    ): List<UserTrackAffinity>

    /**
     * HNSW kNN search: tracks closest to [seedTrackId] in MERT embedding space (cosine distance),
     * with a fallback to CLAP, then Discogs. Empty list if the seed has no embeddings yet.
     * The seed itself is excluded from the result.
     */
    fun findSimilarTracks(
        seedTrackId: TrackId,
        limit: Int,
    ): List<TrackId>

    /**
     * HNSW kNN search over the CLAP audio embedding space for a raw 512-dim query vector
     * (e.g. a CLAP text embedding), ordered by cosine distance. Empty if no tracks carry a
     * CLAP embedding yet.
     */
    fun findTracksByClapVector(
        vector: FloatArray,
        limit: Int,
    ): List<TrackId>

    /**
     * Representative tracks (medoids) of the user's taste clusters, biggest facet first.
     * Empty until the taste-clusters batch job has run for the user.
     */
    fun getTasteClusterRepresentatives(userId: UserId): List<TrackId>

    /**
     * A wide, similarity-ordered candidate pool for building a varied radio station from a single
     * seed (MERT, falling back to CLAP, then Discogs). Unlike [findSimilarTracks] this raises the
     * HNSW `ef_search` runtime so a large [poolSize] is actually recalled instead of being silently
     * truncated at the pgvector default; callers then diversify the pool (per-album / per-artist
     * caps) to break the same-album nearest-neighbour clustering. Ordered most-similar first; the
     * seed is excluded. Empty when the seed has no embedding in any space.
     */
    fun findRadioPool(
        seedTrackId: TrackId,
        poolSize: Int,
    ): List<TrackId>

    /** Fraction of the library that carries each embedding type — for operator-visible coverage gauges. */
    fun embeddingCoverage(): EmbeddingCoverage
}

data class EmbeddingCoverage(
    val total: Long,
    val mert: Long,
    val clap: Long,
    val discogs: Long,
    val musicnn: Long,
)
