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
}
