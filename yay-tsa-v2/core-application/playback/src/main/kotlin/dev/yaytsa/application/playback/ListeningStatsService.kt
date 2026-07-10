package dev.yaytsa.application.playback

import dev.yaytsa.application.playback.port.PlayHistoryEvent
import dev.yaytsa.application.playback.port.PlayHistoryQueryPort
import dev.yaytsa.shared.TrackId
import dev.yaytsa.shared.UserId
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import kotlin.math.sqrt

enum class ListeningStatsGroupBy { ARTIST, GENRE, HOUR, WEEKDAY, SOURCE }

data class ListeningStatRow(
    val group: String,
    val plays: Int,
    val completions: Int,
    val skips: Int,
    val skipRate: Double,
    val skipRateCiLow: Double,
    val skipRateCiHigh: Double,
    val playedMinutes: Long,
    val lowSupport: Boolean,
)

data class ListeningStatsResult(
    val windowStart: Instant,
    val windowEnd: Instant,
    val totalEvents: Int,
    val eventsMissingDuration: Int,
    val rows: List<ListeningStatRow>,
)

class ListeningStatsService(
    private val playHistoryQuery: PlayHistoryQueryPort,
    private val trackGroupResolver: (Collection<TrackId>, ListeningStatsGroupBy) -> Map<TrackId, String>,
) {
    fun stats(
        userId: UserId,
        since: Instant,
        until: Instant,
        groupBy: ListeningStatsGroupBy,
        zone: ZoneId,
    ): ListeningStatsResult {
        val events = playHistoryQuery.eventsInWindow(userId, since, until)
        val trackGroups =
            when (groupBy) {
                ListeningStatsGroupBy.ARTIST, ListeningStatsGroupBy.GENRE ->
                    trackGroupResolver(events.map { it.trackId }.distinct(), groupBy)
                else -> emptyMap()
            }
        val rows =
            events
                .groupBy { groupKey(it, groupBy, trackGroups, zone) }
                .map { (group, groupEvents) -> statRow(group, groupEvents) }
                .sortedByDescending { it.plays }
        return ListeningStatsResult(
            windowStart = since,
            windowEnd = until,
            totalEvents = events.size,
            eventsMissingDuration = events.count { it.durationMs == null || it.durationMs == 0L },
            rows = rows,
        )
    }

    private fun groupKey(
        event: PlayHistoryEvent,
        groupBy: ListeningStatsGroupBy,
        trackGroups: Map<TrackId, String>,
        zone: ZoneId,
    ): String {
        val local = event.startedAt.atZone(zone)
        return when (groupBy) {
            ListeningStatsGroupBy.ARTIST, ListeningStatsGroupBy.GENRE ->
                trackGroups[event.trackId] ?: UNKNOWN_GROUP
            ListeningStatsGroupBy.HOUR -> "%02d:00".format(local.hour)
            ListeningStatsGroupBy.WEEKDAY -> local.dayOfWeek.name
            ListeningStatsGroupBy.SOURCE -> event.source ?: UNKNOWN_GROUP
        }
    }

    private fun statRow(
        group: String,
        events: List<PlayHistoryEvent>,
    ): ListeningStatRow {
        val n = events.size
        val skips = events.count { it.skipped }
        val (ciLow, ciHigh) = wilsonInterval(skips, n)
        return ListeningStatRow(
            group = group,
            plays = n,
            completions = events.count { it.completed },
            skips = skips,
            skipRate = if (n > 0) skips.toDouble() / n else 0.0,
            skipRateCiLow = ciLow,
            skipRateCiHigh = ciHigh,
            playedMinutes = Duration.ofMillis(events.sumOf { clampedPlayedMs(it) }).toMinutes(),
            lowSupport = n < LOW_SUPPORT_THRESHOLD,
        )
    }

    private fun clampedPlayedMs(event: PlayHistoryEvent): Long {
        val cap = event.durationMs?.takeIf { it > 0 } ?: MAX_EVENT_PLAYED_MS
        return (event.playedMs ?: 0L).coerceIn(0L, minOf(cap, MAX_EVENT_PLAYED_MS))
    }

    // Wilson score interval at 95%: honest uncertainty on small per-group samples, so the
    // consumer sees "3 skips of 5 plays" as the wide interval it is, not a confident 60%.
    private fun wilsonInterval(
        successes: Int,
        n: Int,
    ): Pair<Double, Double> {
        if (n == 0) return 0.0 to 0.0
        val z = 1.96
        val p = successes.toDouble() / n
        val denominator = 1 + z * z / n
        val center = p + z * z / (2 * n)
        val margin = z * sqrt((p * (1 - p) + z * z / (4 * n)) / n)
        val low = ((center - margin) / denominator).coerceIn(0.0, 1.0)
        val high = ((center + margin) / denominator).coerceIn(0.0, 1.0)
        return low to high
    }

    companion object {
        const val LOW_SUPPORT_THRESHOLD = 20
        const val MAX_EVENT_PLAYED_MS: Long = 86_400_000
        const val UNKNOWN_GROUP = "Unknown"
    }
}
