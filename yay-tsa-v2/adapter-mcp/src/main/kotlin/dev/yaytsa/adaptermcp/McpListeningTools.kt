package dev.yaytsa.adaptermcp

import dev.yaytsa.application.library.LibraryQueries
import dev.yaytsa.application.ml.port.MlQueryPort
import dev.yaytsa.application.playback.ListeningStatRow
import dev.yaytsa.application.playback.ListeningStatsGroupBy
import dev.yaytsa.application.playback.ListeningStatsResult
import dev.yaytsa.application.playback.ListeningStatsService
import dev.yaytsa.application.playback.port.PlayHistoryEvent
import dev.yaytsa.application.playback.port.PlayHistoryQueryPort
import dev.yaytsa.application.shared.port.Clock
import dev.yaytsa.domain.library.Track
import dev.yaytsa.shared.EntityId
import dev.yaytsa.shared.TrackId
import dev.yaytsa.shared.UserId
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

@Component
class McpListeningTools(
    private val listeningStatsService: ListeningStatsService,
    private val playHistoryQuery: PlayHistoryQueryPort,
    private val libraryQueries: LibraryQueries,
    private val mlQuery: MlQueryPort,
    private val clock: Clock,
) : McpToolProvider {
    override fun definitions(): List<McpToolDefinition> =
        listOf(
            McpToolDefinition(
                "get_listening_stats",
                "Aggregate the user's listening history (play_history scrobbles — ALL listening, every protocol). " +
                    "Each row carries plays/skips/completions, a Wilson 95% CI on the skip rate, and a " +
                    "LOW SUPPORT flag at n<20 — never state patterns from LOW SUPPORT rows.",
                mapOf(
                    "type" to "object",
                    "properties" to
                        mapOf(
                            "window_days" to
                                mapOf("type" to "integer", "default" to 30, "minimum" to 1, "maximum" to 180),
                            "group_by" to
                                mapOf("type" to "string", "enum" to listOf("artist", "genre", "hour", "weekday", "source")),
                            "tz" to
                                mapOf(
                                    "type" to "string",
                                    "description" to "IANA timezone for hour/weekday buckets (e.g. Europe/Berlin); default UTC",
                                ),
                        ),
                    "required" to listOf("group_by"),
                ),
            ),
            McpToolDefinition(
                "get_listening_history",
                "Raw recent listening events (play_history scrobbles, newest first) with track and artist names joined.",
                mapOf(
                    "type" to "object",
                    "properties" to
                        mapOf(
                            "window_days" to
                                mapOf("type" to "integer", "default" to 30, "minimum" to 1, "maximum" to 180),
                            "source" to
                                mapOf("type" to "string", "description" to "Optional source filter (e.g. queue, adaptive, subsonic)"),
                            "limit" to mapOf("type" to "integer", "default" to 50, "minimum" to 1, "maximum" to 200),
                            "offset" to mapOf("type" to "integer", "default" to 0),
                        ),
                ),
            ),
            McpToolDefinition(
                "get_track",
                "Look up one track by id: name, artist, album, genre, duration. " +
                    "response_format=detailed adds play stats and the adaptive-signal affinity block.",
                mapOf(
                    "type" to "object",
                    "properties" to
                        mapOf(
                            "track_id" to mapOf("type" to "string"),
                            "response_format" to
                                mapOf("type" to "string", "enum" to listOf("concise", "detailed"), "default" to "concise"),
                        ),
                    "required" to listOf("track_id"),
                ),
            ),
        )

    override fun handles(name: String): Boolean = name in setOf("get_listening_stats", "get_listening_history", "get_track")

    override fun execute(
        name: String,
        clientArgs: Map<String, Any?>,
        authenticatedUserId: String,
    ): McpToolResult {
        val uid = UserId(authenticatedUserId)
        return when (name) {
            "get_listening_stats" -> getListeningStats(clientArgs, uid)
            "get_listening_history" -> getListeningHistory(clientArgs, uid)
            "get_track" -> getTrack(clientArgs, uid)
            else -> errorResult("Unknown tool: $name")
        }
    }

    private fun getListeningStats(
        args: Map<String, Any?>,
        uid: UserId,
    ): McpToolResult {
        val windowDays = intArg(args, "window_days", DEFAULT_WINDOW_DAYS).coerceIn(1, MAX_WINDOW_DAYS)
        val groupByRaw = args["group_by"] as? String ?: return errorResult("group_by is required: artist|genre|hour|weekday|source")
        val groupBy =
            runCatching { ListeningStatsGroupBy.valueOf(groupByRaw.uppercase(Locale.ROOT)) }.getOrNull()
                ?: return errorResult("Invalid group_by '$groupByRaw' — use artist|genre|hour|weekday|source")
        val tzRaw = args["tz"] as? String
        val zone =
            if (tzRaw == null) {
                ZoneOffset.UTC
            } else {
                runCatching { ZoneId.of(tzRaw) }.getOrNull()
                    ?: return errorResult("Invalid tz '$tzRaw' — use an IANA zone like Europe/Berlin, or omit for UTC")
            }
        val until = clock.now()
        val since = until.minus(Duration.ofDays(windowDays.toLong()))
        val result = listeningStatsService.stats(uid, since, until, groupBy, zone)
        return textResult(statsText(result, zone))
    }

    private fun statsText(
        result: ListeningStatsResult,
        zone: ZoneId,
    ): String =
        buildString {
            append("source: play_history, ${result.totalEvents} events, ")
            append("window ${DATE.format(result.windowStart.atZone(zone))} .. ${DATE.format(result.windowEnd.atZone(zone))} ")
            append("(tz $zone), ${result.eventsMissingDuration} events missing duration (pre-fix rows)")
            if (result.rows.isEmpty()) {
                append("\nNo listening events in this window.")
                return@buildString
            }
            result.rows.take(MAX_STAT_ROWS).forEach { append("\n").append(statRowLine(it)) }
            val more = result.rows.size - MAX_STAT_ROWS
            if (more > 0) append("\n…and $more more groups")
        }

    private fun statRowLine(row: ListeningStatRow): String =
        buildString {
            append("${row.group}: plays=${row.plays} skips=${row.skips} skip_rate=${rate(row.skipRate)} ")
            append("[CI ${rate(row.skipRateCiLow)}-${rate(row.skipRateCiHigh)}] ")
            append("completed=${row.completions} minutes=${row.playedMinutes}")
            if (row.lowSupport) append(" LOW SUPPORT (n<${ListeningStatsService.LOW_SUPPORT_THRESHOLD})")
        }

    private fun getListeningHistory(
        args: Map<String, Any?>,
        uid: UserId,
    ): McpToolResult {
        val windowDays = intArg(args, "window_days", DEFAULT_WINDOW_DAYS).coerceIn(1, MAX_WINDOW_DAYS)
        val source = args["source"] as? String
        val limit = intArg(args, "limit", DEFAULT_HISTORY_LIMIT).coerceIn(1, MAX_HISTORY_LIMIT)
        val offset = intArg(args, "offset", 0).coerceAtLeast(0)
        val until = clock.now()
        val since = until.minus(Duration.ofDays(windowDays.toLong()))
        val sourceSuffix = source?.let { " for source=$it" } ?: ""
        val total = playHistoryQuery.historyCount(uid, since, until, source)
        if (total == 0L) {
            return textResult("source: play_history — no listening events in the last $windowDays days$sourceSuffix.")
        }
        val events = playHistoryQuery.historyPage(uid, since, until, source, limit, offset)
        if (events.isEmpty()) {
            return textResult(
                "source: play_history, $total events in the last $windowDays days$sourceSuffix — " +
                    "offset $offset is beyond the last event, use a smaller offset.",
            )
        }
        val tracksById =
            libraryQueries
                .getTracksByIds(events.map { EntityId(it.trackId.value) }.distinct())
                .associateBy { it.id.value }
        val artistNames = libraryQueries.getEntityNamesByIds(tracksById.values.mapNotNull { it.albumArtistId }.toSet())
        return textResult(
            buildString {
                append("source: play_history, $total events in the last $windowDays days$sourceSuffix, ")
                append("showing ${offset + 1}-${offset + events.size} (newest first)")
                if (offset + events.size < total) append(" — next page: offset=${offset + events.size}")
                events.forEach { event ->
                    val track = tracksById[event.trackId.value]
                    append("\n").append(historyLine(event, track, track?.albumArtistId?.let { artistNames[it] }))
                }
            },
        )
    }

    private fun historyLine(
        event: PlayHistoryEvent,
        track: Track?,
        artistName: String?,
    ): String {
        val trackLabel = track?.let { "${it.name} (id: ${it.id.value})" } ?: "unknown track (id: ${event.trackId.value})"
        val playedMs = event.playedMs
        val durationMs = event.durationMs?.takeIf { it > 0 }
        val played = playedMs?.let { msToClock(it) } ?: "?"
        val duration = durationMs?.let { msToClock(it) } ?: "?"
        val percent = if (playedMs != null && durationMs != null) " (${playedMs * 100 / durationMs}%)" else ""
        val status =
            when {
                event.completed -> "completed"
                event.skipped -> "skipped"
                else -> "partial"
            }
        return "${TIME_UTC.format(event.startedAt.atOffset(ZoneOffset.UTC))} — $trackLabel — ${artistName ?: "unknown artist"} — " +
            "played $played/$duration$percent — $status — source=${event.source ?: "unknown"}"
    }

    private fun getTrack(
        args: Map<String, Any?>,
        uid: UserId,
    ): McpToolResult {
        val trackId = args["track_id"] as? String ?: return errorResult("track_id is required")
        val format = args["response_format"] as? String ?: "concise"
        if (format != "concise" && format != "detailed") {
            return errorResult("Invalid response_format '$format' — use 'concise' or 'detailed'")
        }
        val track =
            libraryQueries.getTrack(EntityId(trackId))
                ?: return errorResult("Track not found: $trackId. Use search_library or get_album to find valid track ids.")
        val names = libraryQueries.getEntityNamesByIds(setOfNotNull(track.albumArtistId, track.albumId))
        val summary =
            buildString {
                append("${track.name} (id: ${track.id.value}) — ${track.albumArtistId?.let { names[it] } ?: "unknown artist"}")
                append(" — album ${track.albumId?.let { names[it] } ?: "unknown"}")
                append(" — genre ${track.genre ?: "unknown"}")
                append(" — ${track.durationMs?.let { msToClock(it) } ?: "unknown duration"}")
            }
        if (format == "concise") return textResult(summary)
        return textResult(summary + "\n" + trackDetailLines(uid, track))
    }

    private fun trackDetailLines(
        uid: UserId,
        track: Track,
    ): String {
        val trackId = TrackId(track.id.value)
        val plays = playHistoryQuery.playCountsByTrackIds(listOf(trackId))[trackId] ?: 0L
        val affinity = mlQuery.getUserTrackAffinity(uid, trackId)
        val affinityLine =
            affinity?.let {
                "Affinity (adaptive-signals only — excludes non-DJ listening): score=${rate(it.affinityScore)}, " +
                    "plays=${it.playCount}, completions=${it.completionCount}, skips=${it.skipCount}, " +
                    "thumbs +${it.thumbsUpCount}/-${it.thumbsDownCount}"
            } ?: "Affinity (adaptive-signals only): none recorded — track has not been played in a DJ session yet."
        return "Play stats (play_history, all listeners): $plays plays\n$affinityLine"
    }

    private companion object {
        const val DEFAULT_WINDOW_DAYS = 30
        const val MAX_WINDOW_DAYS = 180
        const val DEFAULT_HISTORY_LIMIT = 50
        const val MAX_HISTORY_LIMIT = 200
        const val MAX_STAT_ROWS = 30
        val DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.ROOT)
        val TIME_UTC: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'", Locale.ROOT)
    }
}
