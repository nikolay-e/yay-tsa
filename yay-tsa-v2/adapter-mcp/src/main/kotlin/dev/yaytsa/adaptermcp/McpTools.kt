package dev.yaytsa.adaptermcp

import dev.yaytsa.adaptershared.AdapterCommandContextFactory
import dev.yaytsa.adaptershared.toMcpJson
import dev.yaytsa.application.adaptive.AdaptiveUseCases
import dev.yaytsa.application.library.LibraryQueries
import dev.yaytsa.application.playback.PlaybackQueries
import dev.yaytsa.application.playback.PlaybackUseCases
import dev.yaytsa.application.playlists.PlaylistQueries
import dev.yaytsa.application.playlists.PlaylistUseCases
import dev.yaytsa.application.preferences.PreferencesQueries
import dev.yaytsa.application.preferences.PreferencesUseCases
import dev.yaytsa.domain.adaptive.ListeningSessionId
import dev.yaytsa.domain.adaptive.StartListeningSession
import dev.yaytsa.domain.playback.AddToQueue
import dev.yaytsa.domain.playback.ClearQueue
import dev.yaytsa.domain.playback.Pause
import dev.yaytsa.domain.playback.Play
import dev.yaytsa.domain.playback.QueueEntry
import dev.yaytsa.domain.playback.QueueEntryId
import dev.yaytsa.domain.playback.SessionId
import dev.yaytsa.domain.playback.SkipNext
import dev.yaytsa.domain.playback.SkipPrevious
import dev.yaytsa.domain.preferences.UpdatePreferenceContract
import dev.yaytsa.shared.AggregateVersion
import dev.yaytsa.shared.CommandResult
import dev.yaytsa.shared.DeviceId
import dev.yaytsa.shared.EntityId
import dev.yaytsa.shared.TrackId
import dev.yaytsa.shared.UserId
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Component
import java.util.UUID

@Component
class McpTools(
    private val libraryQueries: LibraryQueries,
    private val playbackQueries: PlaybackQueries,
    private val playbackUseCases: PlaybackUseCases,
    private val playlistQueries: PlaylistQueries,
    @Suppress("unused")
    private val playlistUseCases: PlaylistUseCases,
    private val preferencesQueries: PreferencesQueries,
    private val preferencesUseCases: PreferencesUseCases,
    private val adaptiveUseCases: AdaptiveUseCases,
    @Qualifier("mcpCommandContextFactory")
    private val ctxFactory: AdapterCommandContextFactory,
) {
    fun listTools(): List<McpToolDefinition> =
        listOf(
            McpToolDefinition(
                "search_library",
                "Search the music library for artists, albums, and tracks",
                mapOf(
                    "type" to "object",
                    "properties" to mapOf("query" to mapOf("type" to "string", "description" to "Search query")),
                    "required" to listOf("query"),
                ),
            ),
            McpToolDefinition(
                "get_playback_state",
                "Get current playback state for a session",
                mapOf(
                    "type" to "object",
                    "properties" to
                        mapOf(
                            "user_id" to mapOf("type" to "string"),
                            "session_id" to mapOf("type" to "string"),
                        ),
                    "required" to listOf("user_id", "session_id"),
                ),
            ),
            McpToolDefinition(
                "play",
                "Start or resume playback",
                mapOf(
                    "type" to "object",
                    "properties" to
                        mapOf(
                            "user_id" to mapOf("type" to "string"),
                            "session_id" to mapOf("type" to "string"),
                            "device_id" to mapOf("type" to "string"),
                        ),
                    "required" to listOf("user_id", "session_id", "device_id"),
                ),
            ),
            McpToolDefinition(
                "pause",
                "Pause playback",
                mapOf(
                    "type" to "object",
                    "properties" to
                        mapOf(
                            "user_id" to mapOf("type" to "string"),
                            "session_id" to mapOf("type" to "string"),
                            "device_id" to mapOf("type" to "string"),
                        ),
                    "required" to listOf("user_id", "session_id", "device_id"),
                ),
            ),
            McpToolDefinition(
                "skip_next",
                "Skip to next track",
                mapOf(
                    "type" to "object",
                    "properties" to
                        mapOf(
                            "user_id" to mapOf("type" to "string"),
                            "session_id" to mapOf("type" to "string"),
                            "device_id" to mapOf("type" to "string"),
                        ),
                    "required" to listOf("user_id", "session_id", "device_id"),
                ),
            ),
            McpToolDefinition(
                "skip_previous",
                "Skip to previous track",
                mapOf(
                    "type" to "object",
                    "properties" to
                        mapOf(
                            "user_id" to mapOf("type" to "string"),
                            "session_id" to mapOf("type" to "string"),
                            "device_id" to mapOf("type" to "string"),
                        ),
                    "required" to listOf("user_id", "session_id", "device_id"),
                ),
            ),
            McpToolDefinition(
                "browse_artists",
                "Browse all artists in the library",
                mapOf(
                    "type" to "object",
                    "properties" to
                        mapOf(
                            "limit" to mapOf("type" to "integer", "default" to 50),
                            "offset" to mapOf("type" to "integer", "default" to 0),
                        ),
                ),
            ),
            McpToolDefinition(
                "get_album",
                "Get album details with tracks",
                mapOf(
                    "type" to "object",
                    "properties" to mapOf("album_id" to mapOf("type" to "string")),
                    "required" to listOf("album_id"),
                ),
            ),
            McpToolDefinition(
                "list_playlists",
                "List user's playlists",
                mapOf(
                    "type" to "object",
                    "properties" to mapOf("user_id" to mapOf("type" to "string")),
                    "required" to listOf("user_id"),
                ),
            ),
            McpToolDefinition(
                "set_preference_contract",
                "Modify the user's preference contract for the adaptive DJ. Any omitted field keeps its current value.",
                mapOf(
                    "type" to "object",
                    "properties" to
                        mapOf(
                            "hard_rules" to mapOf("type" to "string", "description" to "Inviolable rules, e.g. 'never play screamo'"),
                            "soft_prefs" to mapOf("type" to "string", "description" to "Soft preferences, e.g. 'prefer jazz in the evening'"),
                            "dj_style" to mapOf("type" to "string", "description" to "DJ style, e.g. 'transition smoothly between genres'"),
                            "red_lines" to mapOf("type" to "string", "description" to "Hard red lines the DJ must never cross"),
                        ),
                ),
            ),
            McpToolDefinition(
                "add_to_queue",
                "Add tracks to the end of the playback queue (requires the device to hold the playback lease)",
                mapOf(
                    "type" to "object",
                    "properties" to
                        mapOf(
                            "session_id" to mapOf("type" to "string"),
                            "device_id" to mapOf("type" to "string"),
                            "track_ids" to mapOf("type" to "array", "items" to mapOf("type" to "string")),
                        ),
                    "required" to listOf("session_id", "device_id", "track_ids"),
                ),
            ),
            McpToolDefinition(
                "clear_queue",
                "Clear the playback queue (requires the device to hold the playback lease)",
                mapOf(
                    "type" to "object",
                    "properties" to
                        mapOf(
                            "session_id" to mapOf("type" to "string"),
                            "device_id" to mapOf("type" to "string"),
                        ),
                    "required" to listOf("session_id", "device_id"),
                ),
            ),
            McpToolDefinition(
                "start_radio",
                "Steer adaptive behavior: start a radio/adaptive listening session, optionally seeded by a track. Returns the new session id.",
                mapOf(
                    "type" to "object",
                    "properties" to
                        mapOf(
                            "seed_track_id" to mapOf("type" to "string", "description" to "Optional track id to seed similarity-driven radio"),
                            "attention_mode" to mapOf("type" to "string", "default" to "active"),
                        ),
                ),
            ),
        )

    fun executeTool(
        name: String,
        clientArgs: Map<String, Any?>,
        authenticatedUserId: String,
    ): McpToolResult {
        val args = clientArgs + mapOf("user_id" to authenticatedUserId)
        return when (name) {
            "search_library" -> searchLibrary(args["query"] as? String ?: "")
            "get_playback_state" -> getPlaybackState(args)
            "play" -> playbackCommand(args) { _, sid, did -> Play(sid, did, null) }
            "pause" -> playbackCommand(args) { _, sid, did -> Pause(sid, did) }
            "skip_next" -> playbackCommand(args) { _, sid, did -> SkipNext(sid, did) }
            "skip_previous" -> playbackCommand(args) { _, sid, did -> SkipPrevious(sid, did) }
            "browse_artists" -> browseArtists(args)
            "get_album" -> getAlbum(args)
            "list_playlists" -> listPlaylists(args)
            "set_preference_contract" -> setPreferenceContract(args)
            "add_to_queue" -> addToQueue(args)
            "clear_queue" -> clearQueue(args)
            "start_radio" -> startRadio(args)
            else -> errorResult("Unknown tool: $name")
        }
    }

    private fun searchLibrary(query: String): McpToolResult {
        val results = libraryQueries.searchText(query, 20, 0)
        val text =
            buildString {
                if (results.artists.isNotEmpty()) {
                    appendLine("Artists:")
                    results.artists.forEach { appendLine("  - ${it.name} (id: ${it.id.value})") }
                }
                if (results.albums.isNotEmpty()) {
                    appendLine("Albums:")
                    results.albums.forEach { appendLine("  - ${it.name} (id: ${it.id.value})") }
                }
                if (results.tracks.isNotEmpty()) {
                    appendLine("Tracks:")
                    results.tracks.map { it.toMcpJson() }.forEach { appendLine("  - ${it["name"]} (id: ${it["trackId"]})") }
                }
                if (isEmpty()) append("No results found.")
            }
        return textResult(text)
    }

    private fun getPlaybackState(args: Map<String, Any?>): McpToolResult {
        val uid = args["user_id"] as? String ?: return errorResult("user_id is required")
        val sid = args["session_id"] as? String ?: return errorResult("session_id is required")
        val state =
            playbackQueries.getPlaybackState(UserId(uid), SessionId(sid))
                ?: return textResult("No active session found.")
        return textResult(
            "State: ${state.playbackState}, Queue: ${state.queue.size} tracks, Current: ${state.currentEntryId?.value ?: "none"}",
        )
    }

    private fun playbackCommand(
        args: Map<String, Any?>,
        cmdFactory: (UserId, SessionId, DeviceId) -> dev.yaytsa.domain.playback.PlaybackCommand,
    ): McpToolResult {
        val uidStr = args["user_id"] as? String ?: return errorResult("user_id is required")
        val sidStr = args["session_id"] as? String ?: return errorResult("session_id is required")
        val didStr = args["device_id"] as? String ?: return errorResult("device_id is required")
        val uid = UserId(uidStr)
        val sid = SessionId(sidStr)
        val did = DeviceId(didStr)
        val currentState = playbackQueries.getPlaybackState(uid, sid)
        val version = currentState?.version ?: AggregateVersion.INITIAL
        val ctx = ctxFactory.create(uid, version)
        val result = playbackUseCases.execute(cmdFactory(uid, sid, did), ctx)
        return when (result) {
            is CommandResult.Success -> textResult("OK")
            is CommandResult.Failed -> errorResult(result.failure.toString())
        }
    }

    private fun browseArtists(args: Map<String, Any?>): McpToolResult {
        val limit = (args["limit"] as? Number)?.toInt() ?: 50
        val offset = (args["offset"] as? Number)?.toInt() ?: 0
        val artists = libraryQueries.browseArtists(limit.coerceIn(1, 200), offset.coerceAtLeast(0))
        return textResult(
            artists.joinToString("\n") { "- ${it.name} (id: ${it.id.value})" }.ifEmpty { "No artists found." },
        )
    }

    private fun getAlbum(args: Map<String, Any?>): McpToolResult {
        val albumId = args["album_id"] as? String ?: return errorResult("album_id is required")
        val album = libraryQueries.getAlbum(EntityId(albumId)) ?: return errorResult("Album not found")
        val tracks = libraryQueries.browseTracksByAlbum(EntityId(albumId))
        val sb = StringBuilder("Album: ${album.name}\nTracks:\n")
        tracks.map { it.toMcpJson() }.forEach { sb.appendLine("  ${it["trackNumber"] ?: "?"}: ${it["name"]} (id: ${it["trackId"]})") }
        return textResult(sb.toString())
    }

    private fun listPlaylists(args: Map<String, Any?>): McpToolResult {
        val playlists = playlistQueries.findByOwner(UserId(args["user_id"] as String))
        return textResult(
            playlists
                .joinToString("\n") { "- ${it.name} (${it.tracks.size} tracks, id: ${it.id.value})" }
                .ifEmpty { "No playlists." },
        )
    }

    private fun setPreferenceContract(args: Map<String, Any?>): McpToolResult {
        val uid = UserId(args["user_id"] as? String ?: return errorResult("user_id is required"))
        // Read the aggregate ONCE so the merged fields and the OCC expectedVersion come
        // from the same snapshot, and build the context ONCE so updatedAt is the same
        // requestTime the command executes with (single clock read per command).
        val existing = preferencesQueries.find(uid)
        val current = existing?.preferenceContract
        val ctx = ctxFactory.create(uid, existing?.version ?: AggregateVersion.INITIAL)
        val cmd =
            UpdatePreferenceContract(
                userId = uid,
                hardRules = args["hard_rules"] as? String ?: current?.hardRules ?: "",
                softPrefs = args["soft_prefs"] as? String ?: current?.softPrefs ?: "",
                djStyle = args["dj_style"] as? String ?: current?.djStyle ?: "",
                redLines = args["red_lines"] as? String ?: current?.redLines ?: "",
                updatedAt = ctx.requestTime,
            )
        return when (val result = preferencesUseCases.execute(cmd, ctx)) {
            is CommandResult.Success -> textResult("Preference contract updated.")
            is CommandResult.Failed -> errorResult(result.failure.toString())
        }
    }

    private fun addToQueue(args: Map<String, Any?>): McpToolResult {
        val uid = UserId(args["user_id"] as? String ?: return errorResult("user_id is required"))
        val sid = SessionId(args["session_id"] as? String ?: return errorResult("session_id is required"))
        val did = DeviceId(args["device_id"] as? String ?: return errorResult("device_id is required"))
        val trackIds = (args["track_ids"] as? List<*>)?.mapNotNull { it as? String }.orEmpty()
        if (trackIds.isEmpty()) return errorResult("track_ids must contain at least one track id")
        val entries = trackIds.map { QueueEntry(QueueEntryId(UUID.randomUUID().toString()), TrackId(it)) }
        val version = playbackQueries.getPlaybackState(uid, sid)?.version ?: AggregateVersion.INITIAL
        return when (val result = playbackUseCases.execute(AddToQueue(sid, did, entries), ctxFactory.create(uid, version))) {
            is CommandResult.Success -> textResult("Added ${entries.size} track(s) to the queue.")
            is CommandResult.Failed -> errorResult(result.failure.toString())
        }
    }

    private fun clearQueue(args: Map<String, Any?>): McpToolResult {
        val uid = UserId(args["user_id"] as? String ?: return errorResult("user_id is required"))
        val sid = SessionId(args["session_id"] as? String ?: return errorResult("session_id is required"))
        val did = DeviceId(args["device_id"] as? String ?: return errorResult("device_id is required"))
        val version = playbackQueries.getPlaybackState(uid, sid)?.version ?: AggregateVersion.INITIAL
        return when (val result = playbackUseCases.execute(ClearQueue(sid, did), ctxFactory.create(uid, version))) {
            is CommandResult.Success -> textResult("Queue cleared.")
            is CommandResult.Failed -> errorResult(result.failure.toString())
        }
    }

    private fun startRadio(args: Map<String, Any?>): McpToolResult {
        val uid = UserId(args["user_id"] as? String ?: return errorResult("user_id is required"))
        val sessionId = ListeningSessionId(UUID.randomUUID().toString())
        val cmd =
            StartListeningSession(
                sessionId = sessionId,
                attentionMode = args["attention_mode"] as? String ?: "active",
                seedTrackId = (args["seed_track_id"] as? String)?.let { EntityId(it) },
                seedGenres = emptyList(),
            )
        return when (val result = adaptiveUseCases.execute(cmd, ctxFactory.create(uid, AggregateVersion.INITIAL))) {
            is CommandResult.Success -> textResult("Radio session started (id: ${sessionId.value}).")
            is CommandResult.Failed -> errorResult(result.failure.toString())
        }
    }
}
