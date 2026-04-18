package dev.yaytsa.application.preferences.port

import dev.yaytsa.domain.preferences.PreferenceContract
import dev.yaytsa.shared.UserId

interface PreferencesQueryPort {
    fun getPreferenceContract(userId: UserId): PreferenceContract?
}
