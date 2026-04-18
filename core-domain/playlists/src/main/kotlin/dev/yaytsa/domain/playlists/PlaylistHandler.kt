package dev.yaytsa.domain.playlists

import dev.yaytsa.shared.AggregateVersion
import dev.yaytsa.shared.CommandContext
import dev.yaytsa.shared.CommandResult
import dev.yaytsa.shared.Failure
import dev.yaytsa.shared.asCommandFailure
import dev.yaytsa.shared.asSuccess

object PlaylistHandler {
    fun handle(
        snapshot: PlaylistAggregate?,
        cmd: PlaylistCommand,
        ctx: CommandContext,
        deps: PlaylistDeps,
    ): CommandResult<PlaylistAggregate> {
        if (cmd is CreatePlaylist) return handleCreate(snapshot, cmd, ctx)

        if (snapshot == null) {
            return Failure.NotFound("Playlist", cmd.playlistId.value).asCommandFailure()
        }
        if (snapshot.version != ctx.expectedVersion) {
            return CommandResult.Failed(Failure.Conflict(ctx.expectedVersion, snapshot.version))
        }
        if (snapshot.owner != ctx.userId) {
            return Failure.Unauthorized("Not the playlist owner").asCommandFailure()
        }

        return when (cmd) {
            is CreatePlaylist -> error("unreachable")
            is RenamePlaylist -> rename(snapshot, cmd, ctx)
            is UpdatePlaylistDescription -> updateDescription(snapshot, cmd, ctx)
            is SetPlaylistVisibility -> setVisibility(snapshot, cmd, ctx)
            is DeletePlaylist -> delete(snapshot, ctx)
            is AddTracksToPlaylist -> addTracks(snapshot, cmd, deps, ctx)
            is RemoveTracksFromPlaylist -> removeTracks(snapshot, cmd, ctx)
            is ReorderPlaylistTracks -> reorder(snapshot, cmd, ctx)
        }
    }

    private fun handleCreate(
        existing: PlaylistAggregate?,
        cmd: CreatePlaylist,
        ctx: CommandContext,
    ): CommandResult<PlaylistAggregate> {
        if (existing != null) {
            return Failure.InvariantViolation("Playlist already exists: ${cmd.playlistId.value}").asCommandFailure()
        }
        if (cmd.name.isBlank()) {
            return Failure.InvariantViolation("Playlist name cannot be blank").asCommandFailure()
        }
        val v = AggregateVersion.INITIAL.next()
        return PlaylistAggregate(
            id = cmd.playlistId,
            owner = cmd.owner,
            name = cmd.name,
            description = cmd.description,
            isPublic = cmd.isPublic,
            tracks = emptyList(),
            createdAt = cmd.createdAt,
            updatedAt = cmd.createdAt,
            version = v,
        ).asSuccess(v)
    }

    private fun rename(
        s: PlaylistAggregate,
        cmd: RenamePlaylist,
        ctx: CommandContext,
    ): CommandResult<PlaylistAggregate> {
        if (cmd.newName.isBlank()) {
            return Failure.InvariantViolation("Playlist name cannot be blank").asCommandFailure()
        }
        val v = s.version.next()
        return s.copy(name = cmd.newName, updatedAt = ctx.requestTime, version = v).asSuccess(v)
    }

    private fun updateDescription(
        s: PlaylistAggregate,
        cmd: UpdatePlaylistDescription,
        ctx: CommandContext,
    ): CommandResult<PlaylistAggregate> {
        val v = s.version.next()
        return s.copy(description = cmd.description, updatedAt = ctx.requestTime, version = v).asSuccess(v)
    }

    private fun setVisibility(
        s: PlaylistAggregate,
        cmd: SetPlaylistVisibility,
        ctx: CommandContext,
    ): CommandResult<PlaylistAggregate> {
        val v = s.version.next()
        return s.copy(isPublic = cmd.isPublic, updatedAt = ctx.requestTime, version = v).asSuccess(v)
    }

    private fun delete(
        s: PlaylistAggregate,
        ctx: CommandContext,
    ): CommandResult<PlaylistAggregate> {
        // Return snapshot with emptied tracks; use case handles actual deletion
        val v = s.version.next()
        return s.copy(tracks = emptyList(), updatedAt = ctx.requestTime, version = v).asSuccess(v)
    }

    private fun addTracks(
        s: PlaylistAggregate,
        cmd: AddTracksToPlaylist,
        deps: PlaylistDeps,
        ctx: CommandContext,
    ): CommandResult<PlaylistAggregate> {
        val unknown = cmd.trackIds.filter { it !in deps.knownTrackIds }
        if (unknown.isNotEmpty()) {
            return Failure.InvariantViolation("Unknown tracks: ${unknown.joinToString { it.value }}").asCommandFailure()
        }
        val newEntries = cmd.trackIds.map { PlaylistEntry(it, cmd.addedAt) }
        val v = s.version.next()
        return s.copy(tracks = s.tracks + newEntries, updatedAt = ctx.requestTime, version = v).asSuccess(v)
    }

    private fun removeTracks(
        s: PlaylistAggregate,
        cmd: RemoveTracksFromPlaylist,
        ctx: CommandContext,
    ): CommandResult<PlaylistAggregate> {
        val currentIds = s.tracks.map { it.trackId }.toSet()
        val missing = cmd.trackIds.filter { it !in currentIds }
        if (missing.isNotEmpty()) {
            return Failure.NotFound("Track in playlist", missing.joinToString { it.value }).asCommandFailure()
        }
        val toRemove = cmd.trackIds.toSet()
        val v = s.version.next()
        return s.copy(tracks = s.tracks.filter { it.trackId !in toRemove }, updatedAt = ctx.requestTime, version = v).asSuccess(v)
    }

    private fun reorder(
        s: PlaylistAggregate,
        cmd: ReorderPlaylistTracks,
        ctx: CommandContext,
    ): CommandResult<PlaylistAggregate> {
        if (cmd.newOrder.size != s.tracks.size) {
            return Failure.InvariantViolation("Reorder must be a permutation of existing tracks").asCommandFailure()
        }
        // Build a pool of entries grouped by trackId (handles duplicate tracks)
        val pool = s.tracks.groupBy { it.trackId }.mapValues { it.value.toMutableList() }
        val reordered = mutableListOf<PlaylistEntry>()
        for (trackId in cmd.newOrder) {
            val entries = pool[trackId]
            if (entries.isNullOrEmpty()) {
                return Failure.InvariantViolation("Reorder must be a permutation of existing tracks").asCommandFailure()
            }
            reordered.add(entries.removeFirst())
        }
        // Verify all pool entries were consumed (guards against duplicate trackIds in newOrder)
        if (pool.values.any { it.isNotEmpty() }) {
            return Failure.InvariantViolation("Reorder must be a permutation of existing tracks").asCommandFailure()
        }
        val v = s.version.next()
        return s.copy(tracks = reordered, updatedAt = ctx.requestTime, version = v).asSuccess(v)
    }
}
