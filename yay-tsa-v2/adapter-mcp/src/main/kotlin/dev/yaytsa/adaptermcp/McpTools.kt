package dev.yaytsa.adaptermcp

import dev.yaytsa.application.library.LibraryQueries
import dev.yaytsa.application.playback.PlaybackQueries
import dev.yaytsa.application.playback.PlaybackUseCases
import dev.yaytsa.application.playlists.PlaylistQueries
import dev.yaytsa.application.playlists.PlaylistUseCases
import dev.yaytsa.application.preferences.PreferencesUseCases
import dev.yaytsa.application.shared.port.Clock
import dev.yaytsa.domain.playback.DeviceId
import dev.yaytsa.domain.playback.Pause
import dev.yaytsa.domain.playback.Play
import dev.yaytsa.domain.playback.SessionId
import dev.yaytsa.domain.playback.SkipNext
import dev.yaytsa.domain.playback.SkipPrevious
import dev.yaytsa.shared.AggregateVersion
import dev.yaytsa.shared.CommandContext
import dev.yaytsa.shared.CommandResult
import dev.yaytsa.shared.EntityId
import dev.yaytsa.shared.IdempotencyKey
import dev.yaytsa.shared.ProtocolId
import dev.yaytsa.shared.UserId
import org.springframework.stereotype.Component
import java.util.UUID

@Component
class McpTools(
    private val libraryQueries: LibraryQueries,
    private val playbackQueries: PlaybackQueries,
    private val playbackUseCases: PlaybackUseCases,
    private val playlistQueries: PlaylistQueries,
    private val playlistUseCases: PlaylistUseCases,
    private val preferencesUseCases: PreferencesUseCases,
    private val clock: Clock,
) {
    private val mcpProtocol = ProtocolId("MCP")

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
        )

    fun executeTool(
        name: String,
        args: Map<String, Any?>,
    ): McpToolResult =
        when (name) {
            "search_library" -> searchLibrary(args["query"] as? String ?: "")
            "get_playback_state" -> getPlaybackState(args)
            "play" -> playbackCommand(args) { _, sid, did -> Play(sid, did, null) }
            "pause" -> playbackCommand(args) { _, sid, did -> Pause(sid, did) }
            "skip_next" -> playbackCommand(args) { _, sid, did -> SkipNext(sid, did) }
            "skip_previous" -> playbackCommand(args) { _, sid, did -> SkipPrevious(sid, did) }
            "browse_artists" -> browseArtists(args)
            "get_album" -> getAlbum(args)
            "list_playlists" -> listPlaylists(args)
            else -> errorResult("Unknown tool: $name")
        }

    private fun searchLibrary(query: String): McpToolResult {
        val results = libraryQueries.searchText(query, 20, 0)
        val sb = StringBuilder()
        if (results.artists.isNotEmpty()) {
            sb.appendLine("Artists:")
            results.artists.forEach { sb.appendLine("  - ${it.name} (id: ${it.id.value})") }
        }
        if (results.albums.isNotEmpty()) {
            sb.appendLine("Albums:")
            results.albums.forEach { sb.appendLine("  - ${it.name} (id: ${it.id.value})") }
        }
        if (results.tracks.isNotEmpty()) {
            sb.appendLine("Tracks:")
            results.tracks.forEach { sb.appendLine("  - ${it.name} (id: ${it.id.value})") }
        }
        if (sb.isEmpty()) sb.append("No results found.")
        return textResult(sb.toString())
    }

    private fun getPlaybackState(args: Map<String, Any?>): McpToolResult {
        val state =
            playbackQueries.getPlaybackState(
                UserId(args["user_id"] as String),
                SessionId(args["session_id"] as String),
            ) ?: return textResult("No active session found.")
        return textResult(
            "State: ${state.playbackState}, Queue: ${state.queue.size} tracks, Current: ${state.currentEntryId?.value ?: "none"}",
        )
    }

    private fun playbackCommand(
        args: Map<String, Any?>,
        cmdFactory: (UserId, SessionId, DeviceId) -> dev.yaytsa.domain.playback.PlaybackCommand,
    ): McpToolResult {
        val uid = UserId(args["user_id"] as String)
        val sid = SessionId(args["session_id"] as String)
        val did = DeviceId(args["device_id"] as String)
        val currentState = playbackQueries.getPlaybackState(uid, sid)
        val version = currentState?.version ?: AggregateVersion.INITIAL
        val ctx = CommandContext(uid, mcpProtocol, clock.now(), IdempotencyKey(UUID.randomUUID().toString()), version)
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
        val album = libraryQueries.getAlbum(EntityId(args["album_id"] as String)) ?: return errorResult("Album not found")
        val tracks = libraryQueries.browseTracksByAlbum(EntityId(args["album_id"] as String))
        val sb = StringBuilder("Album: ${album.name}\nTracks:\n")
        tracks.forEach { sb.appendLine("  ${it.trackNumber ?: "?"}: ${it.name} (id: ${it.id.value})") }
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
}
