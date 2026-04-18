package dev.yaytsa.domain.preferences

import dev.yaytsa.shared.AggregateVersion
import dev.yaytsa.shared.TrackId
import dev.yaytsa.shared.UserId
import java.time.Instant

data class UserPreferencesAggregate(
    val userId: UserId,
    val favorites: List<Favorite>,
    val preferenceContract: PreferenceContract?,
    val version: AggregateVersion,
) {
    companion object {
        fun empty(userId: UserId) =
            UserPreferencesAggregate(
                userId = userId,
                favorites = emptyList(),
                preferenceContract = null,
                version = AggregateVersion.INITIAL,
            )
    }
}

data class Favorite(
    val trackId: TrackId,
    val favoritedAt: Instant,
    val position: Int,
)

data class PreferenceContract(
    val hardRules: String,
    val softPrefs: String,
    val djStyle: String,
    val redLines: String,
    val updatedAt: Instant,
)
