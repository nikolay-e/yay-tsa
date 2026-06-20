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
}
