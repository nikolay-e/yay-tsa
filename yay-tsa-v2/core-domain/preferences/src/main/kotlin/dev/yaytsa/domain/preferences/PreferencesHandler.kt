package dev.yaytsa.domain.preferences

import dev.yaytsa.shared.CommandContext
import dev.yaytsa.shared.CommandResult
import dev.yaytsa.shared.Failure
import dev.yaytsa.shared.asCommandFailure
import dev.yaytsa.shared.asSuccess
import dev.yaytsa.shared.guardOcc

object PreferencesHandler {
    fun handle(
        snapshot: UserPreferencesAggregate,
        cmd: PreferencesCommand,
        ctx: CommandContext,
        deps: PreferencesDeps,
    ): CommandResult<UserPreferencesAggregate> {
        guardOcc(snapshot.version, ctx.expectedVersion)?.let { return it }
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
        // Newly favorited tracks go to the front of the custom order: the new track takes
        // position 0 and every existing favorite shifts down by one.
        val shifted = snapshot.favorites.map { it.copy(position = it.position + 1) }
        val newFavorites = listOf(Favorite(cmd.trackId, cmd.favoritedAt, 0)) + shifted

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
        // The client can only ever send the favorites it currently sees: pagination caps the
        // page, and the listing drops favorites whose track has vanished from the library. So
        // newOrder is treated as a partial instruction rather than a full permutation —
        // ids that are not current favorites are ignored, and favorites the client did not
        // mention keep their existing relative order, appended after the reordered block.
        val byTrackId = snapshot.favorites.associateBy { it.trackId }
        val mentioned = cmd.newOrder.filter { byTrackId.containsKey(it) }.distinct()
        if (mentioned.isEmpty()) {
            // Nothing actionable (empty or fully-stale order) — no-op, no version bump.
            return snapshot.asSuccess(snapshot.version)
        }
        val mentionedSet = mentioned.toSet()
        val rest =
            snapshot.favorites
                .filterNot { it.trackId in mentionedSet }
                .sortedBy { it.position }
                .map { it.trackId }
        val reordered =
            (mentioned + rest).mapIndexed { i, trackId ->
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
