package dev.yaytsa.domain.preferences

import dev.yaytsa.shared.Command
import dev.yaytsa.shared.TrackId
import dev.yaytsa.shared.UserId
import java.time.Instant

sealed interface PreferencesCommand : Command {
    val userId: UserId
}

data class SetFavorite(
    override val userId: UserId,
    val trackId: TrackId,
    val favoritedAt: Instant,
) : PreferencesCommand

data class UnsetFavorite(
    override val userId: UserId,
    val trackId: TrackId,
) : PreferencesCommand

data class ReorderFavorites(
    override val userId: UserId,
    val newOrder: List<TrackId>,
) : PreferencesCommand

data class UpdatePreferenceContract(
    override val userId: UserId,
    val hardRules: String,
    val softPrefs: String,
    val djStyle: String,
    val redLines: String,
    val updatedAt: Instant,
) : PreferencesCommand
