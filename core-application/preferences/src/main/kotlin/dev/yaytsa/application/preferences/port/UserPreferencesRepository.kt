package dev.yaytsa.application.preferences.port

import dev.yaytsa.domain.preferences.UserPreferencesAggregate
import dev.yaytsa.shared.UserId

interface UserPreferencesRepository {
    fun find(userId: UserId): UserPreferencesAggregate?

    fun save(aggregate: UserPreferencesAggregate)
}
