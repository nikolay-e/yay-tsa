package dev.yaytsa.application.preferences

import dev.yaytsa.application.preferences.port.UserPreferencesRepository
import dev.yaytsa.domain.preferences.UserPreferencesAggregate
import dev.yaytsa.shared.UserId

class PreferencesQueries(
    private val prefsRepo: UserPreferencesRepository,
) {
    fun find(userId: UserId): UserPreferencesAggregate? = prefsRepo.find(userId)
}
