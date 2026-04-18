package dev.yaytsa.domain.preferences

import dev.yaytsa.shared.CommandContext
import dev.yaytsa.shared.CommandResult
import dev.yaytsa.shared.Failure
import dev.yaytsa.shared.asCommandFailure
import dev.yaytsa.shared.asSuccess

object PreferencesHandler {
    fun handle(
        snapshot: UserPreferencesAggregate,
        cmd: PreferencesCommand,
        ctx: CommandContext,
        deps: PreferencesDeps,
    ): CommandResult<UserPreferencesAggregate> {
        if (snapshot.version != ctx.expectedVersion) {
            return CommandResult.Failed(Failure.Conflict(ctx.expectedVersion, snapshot.version))
        }
        if (snapshot.userId != ctx.userId) {
            return Failure.Unauthorized("Cannot modify another user's preferences").asCommandFailure()
        }

        return when (cmd) {
            is SetFavorite -> setFavorite(snapshot, cmd, deps)
            is UnsetFavorite -> unsetFavorite(snapshot, cmd)
            is ReorderFavorites -> reorderFavorites(snapshot, cmd)
            is UpdatePreferenceContract -> updateContract(snapshot, cmd)
        }
    }

    private fun setFavorite(
        snapshot: UserPreferencesAggregate,
        cmd: SetFavorite,
        deps: PreferencesDeps,
    ): CommandResult<UserPreferencesAggregate> {
        if (cmd.trackId !in deps.knownTrackIds) {
            return Failure.NotFound("Track", cmd.trackId.value).asCommandFailure()
        }
        val existing = snapshot.favorites.indexOfFirst { it.trackId == cmd.trackId }
        if (existing >= 0) {
            // Already favorited — return unchanged (true no-op idempotency, no version bump)
            return snapshot.asSuccess(snapshot.version)
        }
        val nextPosition = (snapshot.favorites.maxOfOrNull { it.position } ?: -1) + 1
        val newFavorites = snapshot.favorites + Favorite(cmd.trackId, cmd.favoritedAt, nextPosition)

        val v = snapshot.version.next()
        return snapshot.copy(favorites = newFavorites, version = v).asSuccess(v)
    }

    private fun unsetFavorite(
        snapshot: UserPreferencesAggregate,
        cmd: UnsetFavorite,
    ): CommandResult<UserPreferencesAggregate> {
        val idx = snapshot.favorites.indexOfFirst { it.trackId == cmd.trackId }
        if (idx == -1) {
            return Failure.NotFound("Favorite", cmd.trackId.value).asCommandFailure()
        }
        val remaining = snapshot.favorites.filterNot { it.trackId == cmd.trackId }
        val compacted =
            remaining
                .sortedBy { it.position }
                .mapIndexed { i, fav -> fav.copy(position = i) }
        val v = snapshot.version.next()
        return snapshot.copy(favorites = compacted, version = v).asSuccess(v)
    }

    private fun reorderFavorites(
        snapshot: UserPreferencesAggregate,
        cmd: ReorderFavorites,
    ): CommandResult<UserPreferencesAggregate> {
        val currentIds = snapshot.favorites.map { it.trackId }.toSet()
        if (cmd.newOrder.toSet() != currentIds || cmd.newOrder.size != currentIds.size) {
            return Failure.InvariantViolation("Reorder must be a permutation of current favorites").asCommandFailure()
        }
        val byTrackId = snapshot.favorites.associateBy { it.trackId }
        val reordered =
            cmd.newOrder.mapIndexed { i, trackId ->
                byTrackId[trackId]!!.copy(position = i)
            }
        val v = snapshot.version.next()
        return snapshot.copy(favorites = reordered, version = v).asSuccess(v)
    }

    private fun updateContract(
        snapshot: UserPreferencesAggregate,
        cmd: UpdatePreferenceContract,
    ): CommandResult<UserPreferencesAggregate> {
        val contract =
            PreferenceContract(
                hardRules = cmd.hardRules,
                softPrefs = cmd.softPrefs,
                djStyle = cmd.djStyle,
                redLines = cmd.redLines,
                updatedAt = cmd.updatedAt,
            )
        val v = snapshot.version.next()
        return snapshot.copy(preferenceContract = contract, version = v).asSuccess(v)
    }
}
