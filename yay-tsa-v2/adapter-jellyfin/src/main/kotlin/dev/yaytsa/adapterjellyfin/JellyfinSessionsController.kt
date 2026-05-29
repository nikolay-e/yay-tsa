package dev.yaytsa.adapterjellyfin

import com.fasterxml.jackson.annotation.JsonProperty
import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import dev.yaytsa.adaptershared.AdapterCommandContextFactory
import dev.yaytsa.adaptershared.TICKS_PER_MS
import dev.yaytsa.application.adaptive.AdaptiveUseCases
import dev.yaytsa.application.adaptive.port.AdaptiveQueryPort
import dev.yaytsa.application.adaptive.port.AdaptiveSessionRepository
import dev.yaytsa.application.playback.ScrobbleService
import dev.yaytsa.application.shared.port.Clock
import dev.yaytsa.domain.adaptive.RecordPlaybackSignal
import dev.yaytsa.shared.AggregateVersion
import dev.yaytsa.shared.TrackId
import dev.yaytsa.shared.UserId
import dev.yaytsa.shared.generated.SignalType
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.security.Principal
import java.time.Duration
import java.time.Instant
import java.util.UUID

@RestController
@RequestMapping("/Sessions")
class JellyfinSessionsController(
    private val adaptiveUseCases: AdaptiveUseCases,
    private val adaptiveQuery: AdaptiveQueryPort,
    private val adaptiveSessionRepo: AdaptiveSessionRepository,
    private val scrobbleService: ScrobbleService,
    private val clock: Clock,
    @Qualifier("jellyfinCommandContextFactory")
    private val ctxFactory: AdapterCommandContextFactory,
) {
    private val playbackStarts: Cache<String, Instant> =
        Caffeine
            .newBuilder()
            .expireAfterWrite(Duration.ofHours(6))
            .maximumSize(10_000)
            .build()
    private val log = org.slf4j.LoggerFactory.getLogger(javaClass)

    private fun isValidUuid(value: String): Boolean =
        try {
            UUID.fromString(value)
            true
        } catch (_: IllegalArgumentException) {
            false
        }

    data class PlaybackStartInfo(
        @JsonProperty("ItemId") val itemId: String,
        @JsonProperty("PositionTicks") val positionTicks: Long = 0,
        @JsonProperty("IsPaused") val isPaused: Boolean = false,
        @JsonProperty("CanSeek") val canSeek: Boolean = true,
        @JsonProperty("PlayMethod") val playMethod: String? = null,
        @JsonProperty("MediaSourceId") val mediaSourceId: String? = null,
        @JsonProperty("AudioStreamIndex") val audioStreamIndex: Int? = null,
        @JsonProperty("SubtitleStreamIndex") val subtitleStreamIndex: Int? = null,
        @JsonProperty("VolumeLevel") val volumeLevel: Int? = null,
        @JsonProperty("IsMuted") val isMuted: Boolean? = null,
    )

    data class PlaybackProgressInfo(
        @JsonProperty("ItemId") val itemId: String,
        @JsonProperty("PositionTicks") val positionTicks: Long = 0,
        @JsonProperty("IsPaused") val isPaused: Boolean = false,
        @JsonProperty("CanSeek") val canSeek: Boolean? = null,
        @JsonProperty("PlayMethod") val playMethod: String? = null,
        @JsonProperty("MediaSourceId") val mediaSourceId: String? = null,
        @JsonProperty("AudioStreamIndex") val audioStreamIndex: Int? = null,
        @JsonProperty("SubtitleStreamIndex") val subtitleStreamIndex: Int? = null,
        @JsonProperty("VolumeLevel") val volumeLevel: Int? = null,
        @JsonProperty("IsMuted") val isMuted: Boolean? = null,
    )

    data class PlaybackStopInfo(
        @JsonProperty("ItemId") val itemId: String,
        @JsonProperty("PositionTicks") val positionTicks: Long = 0,
        @JsonProperty("MediaSourceId") val mediaSourceId: String? = null,
    )

    @GetMapping
    fun getSessions(principal: Principal): ResponseEntity<Any> = ResponseEntity.ok(emptyList<Any>())

    @PostMapping("/Playing")
    fun reportPlaying(
        @RequestBody info: PlaybackStartInfo,
        principal: Principal,
    ): ResponseEntity<Void> {
        if (!isValidUuid(info.itemId)) return ResponseEntity.badRequest().build()
        playbackStarts.put("${principal.name}:${info.itemId}", clock.now())
        recordSignal(principal, info.itemId, SignalType.PLAY_START)
        return ResponseEntity.noContent().build()
    }

    @PostMapping("/Playing/Progress")
    fun reportProgress(
        @RequestBody info: PlaybackProgressInfo,
        principal: Principal,
    ): ResponseEntity<Void> {
        if (!isValidUuid(info.itemId)) return ResponseEntity.badRequest().build()
        // Progress reports are too frequent to record as signals
        // Could be used for position tracking in the future
        return ResponseEntity.noContent().build()
    }

    @PostMapping("/Playing/Stopped")
    fun reportStopped(
        @RequestBody info: PlaybackStopInfo,
        principal: Principal,
    ): ResponseEntity<Void> {
        if (!isValidUuid(info.itemId)) return ResponseEntity.badRequest().build()
        val positionMs = info.positionTicks / TICKS_PER_MS
        val uid = UserId(principal.name)
        val trackId = TrackId(info.itemId)
        val now = clock.now()

        // Record stop signal
        recordSignal(principal, info.itemId, SignalType.PLAY_COMPLETE, """{"positionMs":$positionMs}""")

        // Retrieve actual start time from when reportPlaying was called
        val startKey = "${principal.name}:${info.itemId}"
        val startedAt = playbackStarts.getIfPresent(startKey) ?: now
        playbackStarts.invalidate(startKey)

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
        signalType: SignalType,
        signalContext: String? = null,
    ) {
        val uid = UserId(principal.name)
        val activeSession = adaptiveQuery.findActiveSession(uid) ?: return

        val cmd =
            RecordPlaybackSignal(
                sessionId = activeSession.id,
                signalId = UUID.randomUUID().toString(),
                trackId = TrackId(itemId),
                queueEntryId = null,
                signalType = signalType.name,
                signalContext = signalContext,
            )
        val ctx =
            ctxFactory.create(
                uid,
                adaptiveSessionRepo.find(activeSession.id)?.version ?: AggregateVersion.INITIAL,
            )
        try {
            adaptiveUseCases.execute(cmd, ctx)
        } catch (e: Exception) {
            log.warn("Signal recording failed for session {}: {}", activeSession.id.value, e.message)
        }
    }
}
