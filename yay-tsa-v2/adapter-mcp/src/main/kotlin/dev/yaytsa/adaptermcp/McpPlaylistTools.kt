package dev.yaytsa.adaptermcp

import dev.yaytsa.adaptershared.AdapterCommandContextFactory
import dev.yaytsa.application.library.LibraryQueries
import dev.yaytsa.application.playlists.PlaylistQueries
import dev.yaytsa.application.playlists.PlaylistUseCases
import dev.yaytsa.domain.playlists.AddTracksToPlaylist
import dev.yaytsa.domain.playlists.CreatePlaylist
import dev.yaytsa.domain.playlists.PlaylistAggregate
import dev.yaytsa.domain.playlists.PlaylistCommand
import dev.yaytsa.domain.playlists.PlaylistId
import dev.yaytsa.domain.playlists.RemoveTracksFromPlaylist
import dev.yaytsa.domain.playlists.RenamePlaylist
import dev.yaytsa.shared.AggregateVersion
import dev.yaytsa.shared.CommandResult
import dev.yaytsa.shared.EntityId
import dev.yaytsa.shared.TrackId
import dev.yaytsa.shared.UserId
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Component
import java.util.UUID

@Component
class McpPlaylistTools(
    private val playlistQueries: PlaylistQueries,
    private val playlistUseCases: PlaylistUseCases,
    private val libraryQueries: LibraryQueries,
    @Qualifier("mcpCommandContextFactory")
    private val ctxFactory: AdapterCommandContextFactory,
) : McpToolProvider {
    override fun definitions(): List<McpToolDefinition> =
        listOf(
            McpToolDefinition(
                "get_playlist",
                "Get a playlist's name and ordered track list (track names + ids)",
                mapOf(
                    "type" to "object",
                    "properties" to mapOf("playlist_id" to mapOf("type" to "string")),
                    "required" to listOf("playlist_id"),
                ),
            ),
            McpToolDefinition(
                "create_playlist",
                "Create a private playlist with the given tracks (1-100 per call). If a playlist with the " +
                    "same name already exists nothing is created and its id is returned — use update_playlist then.",
                mapOf(
                    "type" to "object",
                    "properties" to
                        mapOf(
                            "name" to mapOf("type" to "string"),
                            "track_ids" to
                                mapOf(
                                    "type" to "array",
                                    "items" to mapOf("type" to "string"),
                                    "minItems" to 1,
                                    "maxItems" to MAX_TRACKS_PER_CALL,
                                ),
                        ),
                    "required" to listOf("name", "track_ids"),
                ),
            ),
            McpToolDefinition(
                "update_playlist",
                "Add tracks, remove tracks (every occurrence of each id) and/or rename a playlist. " +
                    "Resulting size is capped at $MAX_PLAYLIST_SIZE tracks; removing every track requires " +
                    "confirm_empty=true. There is no delete tool — deleting stays in the app.",
                mapOf(
                    "type" to "object",
                    "properties" to
                        mapOf(
                            "playlist_id" to mapOf("type" to "string"),
                            "add_track_ids" to mapOf("type" to "array", "items" to mapOf("type" to "string")),
                            "remove_track_ids" to mapOf("type" to "array", "items" to mapOf("type" to "string")),
                            "new_name" to mapOf("type" to "string"),
                            "confirm_empty" to
                                mapOf(
                                    "type" to "boolean",
                                    "description" to "Must be true when the removal would leave the playlist empty",
                                ),
                        ),
                    "required" to listOf("playlist_id"),
                ),
            ),
        )

    override fun handles(name: String): Boolean = name in setOf("get_playlist", "create_playlist", "update_playlist")

    override fun execute(
        name: String,
        clientArgs: Map<String, Any?>,
        authenticatedUserId: String,
    ): McpToolResult {
        val uid = UserId(authenticatedUserId)
        return when (name) {
            "get_playlist" -> getPlaylist(clientArgs, uid)
            "create_playlist" -> createPlaylist(clientArgs, uid)
            "update_playlist" -> updatePlaylist(clientArgs, uid)
            else -> errorResult("Unknown tool: $name")
        }
    }

    private fun getPlaylist(
        args: Map<String, Any?>,
        uid: UserId,
    ): McpToolResult {
        val playlistId = args["playlist_id"] as? String ?: return errorResult("playlist_id is required")
        val playlist = playlistQueries.find(PlaylistId(playlistId))
        // 404-style response for foreign private playlists so their existence is not confirmed
        if (playlist == null || (!playlist.isPublic && playlist.owner != uid)) {
            return errorResult("Playlist not found: $playlistId. Use list_playlists to see your playlists.")
        }
        val tracks = libraryQueries.getTracksByIds(playlist.tracks.map { EntityId(it.trackId.value) }.distinct())
        val artistNames = libraryQueries.getEntityNamesByIds(tracks.mapNotNull { it.albumArtistId }.toSet())
        // Bare titles are ambiguous (duplicate names across albums, "unknown track" rows) — render
        // "Title — Artist" so a playlist listing is resolvable without per-track round-trips.
        val trackLabels =
            tracks.associate { t ->
                val artist =
                    t.albumArtistId
                        ?.let { artistNames[it] }
                        ?.let { " — $it" }
                        .orEmpty()
                t.id.value to "${t.name}$artist"
            }
        return textResult(
            buildString {
                append("Playlist '${playlist.name}' (id: ${playlist.id.value}, ${playlist.tracks.size} tracks)")
                if (playlist.tracks.isEmpty()) append(" — empty")
                playlist.tracks.take(MAX_PLAYLIST_LINES).forEachIndexed { index, entry ->
                    append("\n${index + 1}. ${trackLabels[entry.trackId.value] ?: "unknown track"} (id: ${entry.trackId.value})")
                }
                val more = playlist.tracks.size - MAX_PLAYLIST_LINES
                if (more > 0) append("\n…and $more more tracks")
            },
        )
    }

    private fun createPlaylist(
        args: Map<String, Any?>,
        uid: UserId,
    ): McpToolResult {
        val name = (args["name"] as? String)?.trim().orEmpty()
        val requestedIds = stringListArg(args, "track_ids")
        val trackIds = requestedIds.distinct()
        val skippedDuplicates = requestedIds.size - trackIds.size
        createValidationError(name, requestedIds, trackIds)?.let { return it }
        // Same-name guard: the MCP client sends no Idempotency-Key header, so an LLM retry of this
        // call would otherwise mint a fresh idempotency key and create a duplicate playlist.
        val existing = playlistQueries.findByOwner(uid).firstOrNull { it.name == name }
        if (existing != null) {
            return textResult(
                "Playlist '$name' already exists (id: ${existing.id.value}, ${existing.tracks.size} tracks) — " +
                    "use update_playlist to modify it, or pick another name.",
            )
        }

        val pid = PlaylistId(UUID.randomUUID().toString())
        val createCtx = ctxFactory.create(uid, AggregateVersion.INITIAL)
        val created =
            when (val result = playlistUseCases.execute(CreatePlaylist(pid, uid, name, null, false, createCtx.requestTime), createCtx)) {
                is CommandResult.Success -> result.value
                is CommandResult.Failed -> return errorResult("Creating playlist '$name' failed: ${result.failure}")
            }
        val addCtx = ctxFactory.create(uid, created.version)
        val addCmd = AddTracksToPlaylist(pid, trackIds.map { TrackId(it) }, addCtx.requestTime)
        return when (val result = playlistUseCases.execute(addCmd, addCtx)) {
            is CommandResult.Success ->
                textResult(
                    "Created playlist '$name' (id: ${pid.value}) with ${trackIds.size} tracks." +
                        if (skippedDuplicates > 0) " Skipped $skippedDuplicates duplicate id(s)." else "",
                )
            is CommandResult.Failed ->
                errorResult(
                    "Playlist '$name' was created (id: ${pid.value}) but adding tracks failed: ${result.failure}. " +
                        "Use update_playlist to add tracks.",
                )
        }
    }

    private fun updatePlaylist(
        args: Map<String, Any?>,
        uid: UserId,
    ): McpToolResult {
        val playlistId = args["playlist_id"] as? String ?: return errorResult("playlist_id is required")
        val playlist = playlistQueries.find(PlaylistId(playlistId))
        if (playlist == null || playlist.owner != uid) {
            return errorResult("Playlist not found: $playlistId. Use list_playlists to see your playlists.")
        }
        val addIds = stringListArg(args, "add_track_ids").distinct()
        val removeIds = stringListArg(args, "remove_track_ids").distinct()
        val newName = (args["new_name"] as? String)?.trim()?.takeIf { it.isNotEmpty() }
        val currentIds = playlist.tracks.map { it.trackId.value }.toSet()
        val toAdd = addIds.filter { it !in currentIds }
        val toRemove = removeIds.filter { it in currentIds }
        val removedEntryCount = playlist.tracks.count { it.trackId.value in toRemove.toSet() }
        val counts =
            UpdateReportCounts(
                alreadyPresent = addIds.size - toAdd.size,
                notInPlaylist = removeIds.size - toRemove.size,
                removedEntryCount = removedEntryCount,
                finalSize = playlist.tracks.size + toAdd.size - removedEntryCount,
            )
        updateValidationError(playlist, addIds, removeIds, newName, toAdd, counts.finalSize, args["confirm_empty"] == true)
            ?.let { return it }
        return applyUpdate(uid, playlist, newName, toAdd, toRemove, counts)
    }

    private fun createValidationError(
        name: String,
        requestedIds: List<String>,
        trackIds: List<String>,
    ): McpToolResult? =
        when {
            name.isEmpty() -> errorResult("name is required")
            requestedIds.isEmpty() -> errorResult("track_ids must contain at least one track id")
            requestedIds.size > MAX_TRACKS_PER_CALL ->
                errorResult(
                    "track_ids is capped at $MAX_TRACKS_PER_CALL per call (got ${requestedIds.size}) — " +
                        "create with the first $MAX_TRACKS_PER_CALL and add the rest via update_playlist.",
                )
            else -> unknownTracksError(trackIds)
        }

    private fun updateValidationError(
        playlist: PlaylistAggregate,
        addIds: List<String>,
        removeIds: List<String>,
        newName: String?,
        toAdd: List<String>,
        finalSize: Int,
        confirmEmpty: Boolean,
    ): McpToolResult? {
        val overlap = addIds.toSet() intersect removeIds.toSet()
        return when {
            addIds.isEmpty() && removeIds.isEmpty() && newName == null ->
                errorResult("Nothing to do — pass add_track_ids, remove_track_ids and/or new_name.")
            overlap.isNotEmpty() ->
                errorResult("These track ids are in both add_track_ids and remove_track_ids: ${overlap.joinToString()}")
            finalSize > MAX_PLAYLIST_SIZE ->
                errorResult(
                    "Resulting playlist would have $finalSize tracks — the cap is $MAX_PLAYLIST_SIZE. Add fewer or remove more tracks.",
                )
            finalSize == 0 && !confirmEmpty ->
                errorResult(
                    "This update would leave playlist '${playlist.name}' empty (${playlist.tracks.size} tracks removed, none added). " +
                        "Pass confirm_empty=true to proceed — there is no delete tool via MCP.",
                )
            else -> unknownTracksError(toAdd)
        }
    }

    private data class UpdateReportCounts(
        val alreadyPresent: Int,
        val notInPlaylist: Int,
        val removedEntryCount: Int,
        val finalSize: Int,
    )

    private fun applyUpdate(
        uid: UserId,
        playlist: PlaylistAggregate,
        newName: String?,
        toAdd: List<String>,
        toRemove: List<String>,
        counts: UpdateReportCounts,
    ): McpToolResult {
        var version = playlist.version
        val applied = mutableListOf<String>()
        val steps = mutableListOf<Pair<String, (java.time.Instant) -> PlaylistCommand>>()
        if (newName != null) steps += "renamed to '$newName'" to { _ -> RenamePlaylist(playlist.id, newName) }
        if (toAdd.isNotEmpty()) {
            val addedLabel = "added ${toAdd.size}" + if (counts.alreadyPresent > 0) " (${counts.alreadyPresent} already present, skipped)" else ""
            steps += addedLabel to { at -> AddTracksToPlaylist(playlist.id, toAdd.map { TrackId(it) }, at) }
        }
        if (toRemove.isNotEmpty()) {
            val removedLabel =
                "removed ${counts.removedEntryCount}" + if (counts.notInPlaylist > 0) " (${counts.notInPlaylist} not in playlist, skipped)" else ""
            steps += removedLabel to { _ -> RemoveTracksFromPlaylist(playlist.id, toRemove.map { TrackId(it) }) }
        }
        if (counts.alreadyPresent > 0 && toAdd.isEmpty()) applied += "added 0 (${counts.alreadyPresent} already present)"
        if (counts.notInPlaylist > 0 && toRemove.isEmpty()) applied += "removed 0 (${counts.notInPlaylist} not in playlist)"
        for ((label, commandFor) in steps) {
            val ctx = ctxFactory.create(uid, version)
            when (val result = playlistUseCases.execute(commandFor(ctx.requestTime), ctx)) {
                is CommandResult.Success -> {
                    version = result.value.version
                    applied += label
                }
                is CommandResult.Failed ->
                    return errorResult(
                        buildString {
                            if (applied.isNotEmpty()) append("Applied: ${applied.joinToString("; ")}. Then the next step failed: ")
                            append("${result.failure}. The playlist may be partially updated — re-check with get_playlist.")
                        },
                    )
            }
        }
        return textResult(
            "Playlist '${newName ?: playlist.name}' (id: ${playlist.id.value}): ${applied.joinToString("; ")} — " +
                "now ${counts.finalSize} tracks.",
        )
    }

    private fun unknownTracksError(trackIds: List<String>): McpToolResult? {
        if (trackIds.isEmpty()) return null
        val requested = trackIds.map { TrackId(it) }.toSet()
        val unknown = requested - libraryQueries.trackIdsExist(requested)
        if (unknown.isEmpty()) return null
        return errorResult(
            "Unknown track ids: ${unknown.joinToString { it.value }}. Use search_library or get_album to find valid ids.",
        )
    }

    private companion object {
        const val MAX_TRACKS_PER_CALL = 100
        const val MAX_PLAYLIST_SIZE = 1000
        const val MAX_PLAYLIST_LINES = 100
    }
}
