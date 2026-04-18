package dev.yaytsa.adapterjellyfin

import com.fasterxml.jackson.annotation.JsonProperty
import dev.yaytsa.application.adaptive.AdaptiveUseCases
import dev.yaytsa.application.adaptive.port.AdaptiveQueryPort
import dev.yaytsa.application.playback.ScrobbleService
import dev.yaytsa.application.shared.port.Clock
import dev.yaytsa.domain.adaptive.RecordPlaybackSignal
import dev.yaytsa.shared.AggregateVersion
import dev.yaytsa.shared.CommandContext
import dev.yaytsa.shared.IdempotencyKey
import dev.yaytsa.shared.ProtocolId
import dev.yaytsa.shared.TrackId
import dev.yaytsa.shared.UserId
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.security.Principal
import java.util.UUID

@RestController
@RequestMapping("/Sessions")
class JellyfinSessionsController(
    private val adaptiveUseCases: AdaptiveUseCases,
    private val adaptiveQuery: AdaptiveQueryPort,
    private val scrobbleService: ScrobbleService,
    private val clock: Clock,
) {
    private val playbackStarts = java.util.concurrent.ConcurrentHashMap<String, java.time.Instant>()
    private val log = org.slf4j.LoggerFactory.getLogger(javaClass)

    data class PlaybackStartInfo(
        @JsonProperty("ItemId") val itemId: String,
        @JsonProperty("PositionTicks") val positionTicks: Long = 0,
        @JsonProperty("IsPaused") val isPaused: Boolean = false,
    )

    data class PlaybackProgressInfo(
        @JsonProperty("ItemId") val itemId: String,
        @JsonProperty("PositionTicks") val positionTicks: Long = 0,
        @JsonProperty("IsPaused") val isPaused: Boolean = false,
    )

    data class PlaybackStopInfo(
        @JsonProperty("ItemId") val itemId: String,
        @JsonProperty("PositionTicks") val positionTicks: Long = 0,
    )

    @GetMapping
    fun getSessions(principal: Principal): ResponseEntity<Any> = ResponseEntity.ok(emptyList<Any>())

    @PostMapping("/Playing")
    fun reportPlaying(
        @RequestBody info: PlaybackStartInfo,
        principal: Principal,
    ): ResponseEntity<Void> {
        playbackStarts["${principal.name}:${info.itemId}"] = clock.now()
        recordSignal(principal, info.itemId, "PLAY_START")
        return ResponseEntity.noContent().build()
    }

    @PostMapping("/Playing/Progress")
    fun reportProgress(
        @RequestBody info: PlaybackProgressInfo,
        principal: Principal,
    ): ResponseEntity<Void> {
        // Progress reports are too frequent to record as signals
        // Could be used for position tracking in the future
        return ResponseEntity.noContent().build()
    }

    @PostMapping("/Playing/Stopped")
    fun reportStopped(
        @RequestBody info: PlaybackStopInfo,
        principal: Principal,
    ): ResponseEntity<Void> {
        val positionMs = info.positionTicks / TICKS_PER_MS
        val uid = UserId(principal.name)
        val trackId = TrackId(info.itemId)
        val now = clock.now()

        // Record stop signal
        recordSignal(principal, info.itemId, "PLAY_COMPLETE")

        // Retrieve actual start time from when reportPlaying was called
        val startKey = "${principal.name}:${info.itemId}"
        val startedAt = playbackStarts.remove(startKey) ?: now

        // Delegate scrobble decision to core-application
        scrobbleService.recordScrobble(
            userId = uid,
            trackId = trackId,
            startedAt = startedAt,
            stoppedAt = now,
            positionMs = positionMs,
        )

        return ResponseEntity.noContent().build()
    }

    private fun recordSignal(
        principal: Principal,
        itemId: String,
        signalType: String,
    ) {
        val uid = UserId(principal.name)
        val activeSession = adaptiveQuery.findActiveSession(uid) ?: return

        val cmd =
            RecordPlaybackSignal(
                sessionId = activeSession.id,
                signalId = UUID.randomUUID().toString(),
                trackId = TrackId(itemId),
                queueEntryId = null,
                signalType = signalType,
                signalContext = null,
            )
        val ctx =
            CommandContext(
                uid,
                ProtocolId("JELLYFIN"),
                clock.now(),
                IdempotencyKey(UUID.randomUUID().toString()),
                AggregateVersion.INITIAL,
            )
        try {
            adaptiveUseCases.execute(cmd, ctx)
        } catch (e: Exception) {
            log.warn("Signal recording failed for session {}: {}", activeSession.id.value, e.message)
        }
    }
}
