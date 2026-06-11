package dev.yaytsa.application.preferences.port

import dev.yaytsa.domain.preferences.UserPreferencesAggregate
import dev.yaytsa.shared.UserId

interface UserPreferencesRepository {
    fun find(userId: UserId): UserPreferencesAggregate?

    fun findFavoriteTrackIds(userId: UserId): Set<String> =
        find(userId)
            ?.favorites
            .orEmpty()
            .map { it.trackId.value }
            .toSet()

    fun save(aggregate: UserPreferencesAggregate)
}
