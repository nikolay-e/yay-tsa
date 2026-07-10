package dev.yaytsa.adaptermcp

import dev.yaytsa.adaptershared.AdapterCommandContextFactory
import dev.yaytsa.adaptershared.toMcpJson
import dev.yaytsa.application.adaptive.AdaptiveUseCases
import dev.yaytsa.application.library.LibraryQueries
import dev.yaytsa.application.playback.DeviceSessionProjection
import dev.yaytsa.application.playback.PlaybackQueries
import dev.yaytsa.application.playback.PlaybackRemoteControl
import dev.yaytsa.application.playback.RemoteControlOutcome
import dev.yaytsa.application.playlists.PlaylistQueries
import dev.yaytsa.application.preferences.PreferencesQueries
import dev.yaytsa.application.preferences.PreferencesUseCases
import dev.yaytsa.domain.adaptive.ListeningSessionId
import dev.yaytsa.domain.adaptive.StartListeningSession
import dev.yaytsa.domain.library.Track
import dev.yaytsa.domain.playback.SessionId
import dev.yaytsa.domain.preferences.UpdatePreferenceContract
import dev.yaytsa.shared.AggregateVersion
import dev.yaytsa.shared.CommandResult
import dev.yaytsa.shared.EntityId
import dev.yaytsa.shared.Failure
import dev.yaytsa.shared.TrackId
import dev.yaytsa.shared.UserId
import dev.yaytsa.shared.generated.RemoteCommandType
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Component
import java.util.UUID

@Component
class McpTools(
    private val libraryQueries: LibraryQueries,
    private val playbackQueries: PlaybackQueries,
    private val playbackRemoteControl: PlaybackRemoteControl,
    private val playlistQueries: PlaylistQueries,
    private val preferencesQueries: PreferencesQueries,
    private val preferencesUseCases: PreferencesUseCases,
    private val adaptiveUseCases: AdaptiveUseCases,
    private val deviceSessionProjection: DeviceSessionProjection,
    private val mlQuery: dev.yaytsa.application.ml.port.MlQueryPort,
    private val embeddingPort: dev.yaytsa.application.ml.port.EmbeddingPort,
    private val musicSurfaceFilter: dev.yaytsa.application.recommendation.MusicSurfaceFilter,
    @Qualifier("mcpCommandContextFactory")
    private val ctxFactory: AdapterCommandContextFactory,
) {
    fun listTools(): List<McpToolDefinition> =
        listOf(
            McpToolDefinition(
                "search_library",
                "Search the music library for artists, albums, and tracks. Set semantic=true for a " +
                    "vibe/mood audio-embedding search over tracks (e.g. 'dreamy synth at sunset').",
                mapOf(
                    "type" to "object",
                    "properties" to
                        mapOf(
                            "query" to mapOf("type" to "string", "description" to "Search query"),
                            "semantic" to
                                mapOf("type" to "boolean", "description" to "Use audio-embedding vibe search over tracks"),
                        ),
                    "required" to listOf("query"),
                ),
            ),
            McpToolDefinition(
                "list_devices",
                "List the caller's active player devices with their session_id, playback state and queue size " +
                    "(playback commands target the active device automatically)",
                mapOf("type" to "object", "properties" to emptyMap<String, Any>()),
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
                "Start or resume playback on the user's active player device (resolved server-side)",
                mapOf("type" to "object", "properties" to emptyMap<String, Any>()),
            ),
            McpToolDefinition(
                "pause",
                "Pause playback on the user's active player device (resolved server-side)",
                mapOf("type" to "object", "properties" to emptyMap<String, Any>()),
            ),
            McpToolDefinition(
                "skip_next",
                "Skip to the next track on the user's active player device",
                mapOf("type" to "object", "properties" to emptyMap<String, Any>()),
            ),
            McpToolDefinition(
                "skip_previous",
                "Skip to the previous track on the user's active player device",
                mapOf("type" to "object", "properties" to emptyMap<String, Any>()),
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
                "get_preference_contract",
                "Read the user's current preference contract for the adaptive DJ",
                mapOf("type" to "object", "properties" to emptyMap<String, Any>()),
            ),
            McpToolDefinition(
                "add_to_queue",
                "Send tracks to the end of the active player device's queue (the device applies them to its local queue)",
                mapOf(
                    "type" to "object",
                    "properties" to
                        mapOf(
                            "track_ids" to mapOf("type" to "array", "items" to mapOf("type" to "string")),
                        ),
                    "required" to listOf("track_ids"),
                ),
            ),
            McpToolDefinition(
                "clear_queue",
                "Clear the active player device's queue (the device empties its local queue and stops)",
                mapOf("type" to "object", "properties" to emptyMap<String, Any>()),
            ),
            McpToolDefinition(
                "start_radio",
                "Start a radio: seeds an adaptive DJ session and replaces the active player device's queue with seed tracks " +
                    "(similar to seed_track_id, or by seed_genres, or random). Requires a reachable player device.",
                mapOf(
                    "type" to "object",
                    "properties" to
                        mapOf(
                            "seed_track_id" to mapOf("type" to "string", "description" to "Optional track id to seed similarity-driven radio"),
                            "seed_genres" to
                                mapOf(
                                    "type" to "array",
                                    "items" to mapOf("type" to "string"),
                                    "description" to "Optional genres to seed the adaptive session",
                                ),
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
            "search_library" ->
                searchLibrary(
                    args["query"] as? String ?: "",
                    args["semantic"] as? Boolean ?: false,
                    UserId(authenticatedUserId),
                )
            "list_devices" -> listDevices(args)
            "get_playback_state" -> getPlaybackState(args)
            "play" -> transportCommand(args, RemoteCommandType.PLAY)
            "pause" -> transportCommand(args, RemoteCommandType.PAUSE)
            "skip_next" -> transportCommand(args, RemoteCommandType.NEXT)
            "skip_previous" -> transportCommand(args, RemoteCommandType.PREV)
            "browse_artists" -> browseArtists(args)
            "get_album" -> getAlbum(args)
            "list_playlists" -> listPlaylists(args)
            "set_preference_contract" -> setPreferenceContract(args)
            "get_preference_contract" -> getPreferenceContract(args)
            "add_to_queue" -> addToQueue(args)
            "clear_queue" -> clearQueue(args)
            "start_radio" -> startRadio(args)
            else -> errorResult("Unknown tool: $name")
        }
    }

    private fun searchLibrary(
        query: String,
        semantic: Boolean,
        userId: UserId,
    ): McpToolResult {
        if (semantic) {
            val semanticResult = semanticSearch(query, userId)
            if (semanticResult != null) return semanticResult
        }
        val results = libraryQueries.searchText(query, 20, 0)
        val tracks = musicSurfaceFilter.filter(results.tracks, userId)
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
                if (tracks.isNotEmpty()) {
                    appendLine("Tracks:")
                    tracks.map { it.toMcpJson() }.forEach { appendLine("  - ${it["name"]} (id: ${it["trackId"]})") }
                }
                if (isEmpty()) append("No results found.")
            }
        return textResult(text)
    }

    private fun semanticSearch(
        query: String,
        userId: UserId,
    ): McpToolResult? {
        val vector = embeddingPort.encodeText(query) ?: return null
        val matches = mlQuery.findTracksByClapVector(vector, 20).mapNotNull { libraryQueries.getTrack(EntityId(it.value)) }
        val tracks = musicSurfaceFilter.filter(matches, userId)
        if (tracks.isEmpty()) return null
        val text =
            buildString {
                appendLine("Tracks (vibe match):")
                tracks.map { it.toMcpJson() }.forEach { appendLine("  - ${it["name"]} (id: ${it["trackId"]})") }
            }
        return textResult(text)
    }

    private fun listDevices(args: Map<String, Any?>): McpToolResult {
        val uid = UserId(args["user_id"] as? String ?: return errorResult("user_id is required"))
        val sessions = deviceSessionProjection.getByUser(uid)
        if (sessions.isEmpty()) {
            return textResult("No active player devices. Open the Yaytsa app on a device, then retry.")
        }
        val text =
            sessions.joinToString("\n") { s ->
                val state = playbackQueries.getPlaybackState(uid, s.sessionId)
                "- device_id: ${s.deviceId.value}, session_id: ${s.sessionId.value}, " +
                    "state: ${state?.playbackState ?: "unknown"}, queue: ${state?.queue?.size ?: 0}, " +
                    "current: ${state?.currentEntryId?.value ?: "none"}, lastSeen: ${s.lastSeenAt}"
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

    private fun transportCommand(
        args: Map<String, Any?>,
        command: RemoteCommandType,
    ): McpToolResult {
        val uid = UserId(args["user_id"] as? String ?: return errorResult("user_id is required"))
        return when (val outcome = playbackRemoteControl.sendTransportCommand(uid, command)) {
            is RemoteControlOutcome.Confirmed -> textResult(confirmedText(command, outcome))
            is RemoteControlOutcome.SentUnconfirmed ->
                textResult(
                    "${command.name} sent to device '${outcome.deviceName ?: "unknown"}' but not yet confirmed — " +
                        "re-check with get_playback_state in a few seconds.",
                )
            else -> unreachableResult(outcome)
        }
    }

    private fun confirmedText(
        command: RemoteCommandType,
        outcome: RemoteControlOutcome.Confirmed,
    ): String =
        when (command) {
            RemoteCommandType.PAUSE -> "Pause confirmed — playback is ${outcome.state}."
            RemoteCommandType.PLAY -> "Play confirmed — playback is ${outcome.state}."
            RemoteCommandType.NEXT, RemoteCommandType.PREV ->
                "Skip confirmed — now playing ${trackLabel(outcome.currentTrackId)}."
            else -> "${command.name} confirmed — playback is ${outcome.state}."
        }

    private fun trackLabel(trackId: TrackId?): String {
        if (trackId == null) return "unknown track"
        return libraryQueries.getTrack(EntityId(trackId.value))?.name ?: trackId.value
    }

    private fun unreachableResult(outcome: RemoteControlOutcome): McpToolResult =
        when (outcome) {
            is RemoteControlOutcome.NoReachableDevice ->
                errorResult(
                    buildString {
                        append("No reachable player device")
                        outcome.deviceName?.let { name ->
                            append(" — device '$name'")
                            outcome.lastSeenAt?.let { append(" last seen $it") }
                        }
                        append(". Open the web player or check list_devices.")
                    },
                )
            is RemoteControlOutcome.NoActiveSession ->
                errorResult("No active playback session — open the web player and start playback, or check list_devices.")
            is RemoteControlOutcome.InvalidTracks ->
                errorResult("Unknown track ids: ${outcome.trackIds.joinToString()}. Use search_library or get_album to find valid ids.")
            is RemoteControlOutcome.Confirmed, is RemoteControlOutcome.SentUnconfirmed ->
                textResult("Command sent.")
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
        val userId = UserId(args["user_id"] as? String ?: return errorResult("user_id is required"))
        val album = libraryQueries.getAlbum(EntityId(albumId)) ?: return errorResult("Album not found")
        val tracks = musicSurfaceFilter.filter(libraryQueries.browseTracksByAlbum(EntityId(albumId)), userId)
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
        val trackIds = (args["track_ids"] as? List<*>)?.mapNotNull { it as? String }.orEmpty()
        if (trackIds.isEmpty()) return errorResult("track_ids must contain at least one track id")
        return when (val outcome = playbackRemoteControl.sendQueueCommand(uid, RemoteCommandType.ENQUEUE, trackIds)) {
            is RemoteControlOutcome.SentUnconfirmed ->
                textResult(
                    "Enqueue of ${trackIds.size} track(s) sent to device '${outcome.deviceName ?: "unknown"}' — " +
                        "the device appends them to its local queue.",
                )
            else -> unreachableResult(outcome)
        }
    }

    private fun clearQueue(args: Map<String, Any?>): McpToolResult {
        val uid = UserId(args["user_id"] as? String ?: return errorResult("user_id is required"))
        return when (val outcome = playbackRemoteControl.sendQueueCommand(uid, RemoteCommandType.CLEAR_QUEUE)) {
            is RemoteControlOutcome.SentUnconfirmed ->
                textResult(
                    "Clear-queue sent to device '${outcome.deviceName ?: "unknown"}' — " +
                        "the device empties its local queue and stops.",
                )
            else -> unreachableResult(outcome)
        }
    }

    private fun startRadio(args: Map<String, Any?>): McpToolResult {
        val uid = UserId(args["user_id"] as? String ?: return errorResult("user_id is required"))
        val sessionId = ListeningSessionId(UUID.randomUUID().toString())
        val seedTrackId = args["seed_track_id"] as? String
        val seedGenres = (args["seed_genres"] as? List<*>)?.mapNotNull { it as? String }.orEmpty()
        val cmd =
            StartListeningSession(
                sessionId = sessionId,
                attentionMode = args["attention_mode"] as? String ?: "active",
                seedTrackId = seedTrackId?.let { EntityId(it) },
                seedGenres = seedGenres,
            )
        when (val result = adaptiveUseCases.execute(cmd, ctxFactory.create(uid, AggregateVersion.INITIAL))) {
            is CommandResult.Success -> {}
            is CommandResult.Failed ->
                return if (result.failure is Failure.InvariantViolation) {
                    textResult("Radio session could not be started (${result.failure}) — retry start_radio once.")
                } else {
                    errorResult(result.failure.toString())
                }
        }
        val seedTracks = resolveRadioTracks(uid, seedTrackId, seedGenres)
        if (seedTracks.isEmpty()) {
            return textResult(
                "Radio session started (id: ${sessionId.value}), but no seed tracks could be resolved — " +
                    "the adaptive DJ will fill the queue as listening signals arrive.",
            )
        }
        val outcome = playbackRemoteControl.sendQueueCommand(uid, RemoteCommandType.SET_QUEUE, seedTracks.map { it.id.value })
        return when (outcome) {
            is RemoteControlOutcome.SentUnconfirmed ->
                textResult(
                    buildString {
                        appendLine(
                            "Radio started (adaptive session id: ${sessionId.value}) — " +
                                "queue of ${seedTracks.size} track(s) sent to device '${outcome.deviceName ?: "unknown"}':",
                        )
                        seedTracks.forEach { appendLine("  - ${it.name} (id: ${it.id.value})") }
                    },
                )
            is RemoteControlOutcome.NoReachableDevice, is RemoteControlOutcome.NoActiveSession ->
                errorResult(
                    "Radio session seeded (id: ${sessionId.value}) but there is no reachable player device to receive the queue — " +
                        "open the web player or check list_devices, then retry.",
                )
            else -> unreachableResult(outcome)
        }
    }

    private fun resolveRadioTracks(
        userId: UserId,
        seedTrackId: String?,
        seedGenres: List<String>,
    ): List<Track> {
        val candidates =
            when {
                seedTrackId != null -> {
                    val similarIds = mlQuery.findSimilarTracks(TrackId(seedTrackId), RADIO_QUEUE_SIZE)
                    listOfNotNull(libraryQueries.getTrack(EntityId(seedTrackId))) +
                        libraryQueries.getTracksByIds(similarIds.map { EntityId(it.value) })
                }
                seedGenres.isNotEmpty() -> libraryQueries.browseTracksByGenreNames(seedGenres).shuffled()
                else -> libraryQueries.browseTracksRandom(RADIO_QUEUE_SIZE)
            }
        return musicSurfaceFilter.filter(candidates.distinctBy { it.id }, userId).take(RADIO_QUEUE_SIZE)
    }

    private fun getPreferenceContract(args: Map<String, Any?>): McpToolResult {
        val uid = UserId(args["user_id"] as? String ?: return errorResult("user_id is required"))
        val contract =
            preferencesQueries.find(uid)?.preferenceContract
                ?: return textResult("No preference contract set — use set_preference_contract to create one.")
        return textResult(
            buildString {
                appendLine("Hard rules: ${contract.hardRules.ifBlank { "(none)" }}")
                appendLine("Soft preferences: ${contract.softPrefs.ifBlank { "(none)" }}")
                appendLine("DJ style: ${contract.djStyle.ifBlank { "(none)" }}")
                appendLine("Red lines: ${contract.redLines.ifBlank { "(none)" }}")
                append("Updated at: ${contract.updatedAt}")
            },
        )
    }

    private companion object {
        const val RADIO_QUEUE_SIZE = 20
    }
}
