package dev.yaytsa.adapterjellyfin

import com.fasterxml.jackson.annotation.JsonProperty
import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import dev.yaytsa.adaptershared.AdapterCommandContextFactory
import dev.yaytsa.adaptershared.TICKS_PER_MS
import dev.yaytsa.application.adaptive.AdaptiveUseCases
import dev.yaytsa.application.adaptive.port.AdaptiveQueryPort
import dev.yaytsa.application.adaptive.port.AdaptiveSessionRepository
import dev.yaytsa.application.library.LibraryQueries
import dev.yaytsa.application.playback.ResumePositionService
import dev.yaytsa.application.playback.ResumeSource
import dev.yaytsa.application.playback.ScrobbleService
import dev.yaytsa.application.shared.port.Clock
import dev.yaytsa.domain.adaptive.RecordPlaybackSignal
import dev.yaytsa.shared.AggregateVersion
import dev.yaytsa.shared.EntityId
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
    private val resumePositionService: ResumePositionService,
    private val libraryQueries: LibraryQueries,
    private val deviceSessionProjection: dev.yaytsa.application.playback.DeviceSessionProjection,
    private val nowPlayingResolver: DeviceNowPlayingResolver,
    private val playbackUseCases: dev.yaytsa.application.playback.PlaybackUseCases,
    private val clock: Clock,
    @Qualifier("jellyfinCommandContextFactory")
    private val ctxFactory: AdapterCommandContextFactory,
) {
    companion object {
        private val REFLECT_LEASE_DURATION: Duration = Duration.ofMinutes(5)
        private val REFLECT_LEASE_RENEW_THRESHOLD: Duration = Duration.ofMinutes(2)
        private const val REFLECT_POSITION_TOLERANCE_MS = 5_000L
        private const val SOURCE_ADAPTIVE = "adaptive"
    }

    private val playbackStarts: Cache<String, Instant> =
        Caffeine
            .newBuilder()
            .expireAfterWrite(Duration.ofHours(6))
            .maximumSize(10_000)
            .build()

    // Last reflected client EventTime per user:device. Comparing client-stamped times against
    // each other (same clock) rejects a reordered stale IsPaused=false report that would
    // otherwise resurrect PLAYING after a newer pause; comparing against server lastKnownAt
    // would be cross-clock and defeated by any client clock skew.
    private val lastReflectedEventTimes: Cache<String, Long> =
        Caffeine
            .newBuilder()
            .expireAfterWrite(Duration.ofMinutes(10))
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
        @JsonProperty("EventName") val eventName: String? = null,
        @JsonProperty("EventTime") val eventTime: Long? = null,
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
        @JsonProperty("EventName") val eventName: String? = null,
        @JsonProperty("EventTime") val eventTime: Long? = null,
    )

    data class PlaybackStopInfo(
        @JsonProperty("ItemId") val itemId: String,
        @JsonProperty("PositionTicks") val positionTicks: Long = 0,
        @JsonProperty("MediaSourceId") val mediaSourceId: String? = null,
        @JsonProperty("EventTime") val eventTime: Long? = null,
    )

    data class SessionNowPlayingItemDto(
        @get:JsonProperty("Id") val id: String,
        @get:JsonProperty("Name") val name: String?,
        @get:JsonProperty("Type") val type: String = "Audio",
    )

    data class SessionPlayStateDto(
        @get:JsonProperty("PositionTicks") val positionTicks: Long,
        @get:JsonProperty("IsPaused") val isPaused: Boolean,
        @get:JsonProperty("CanSeek") val canSeek: Boolean = true,
    )

    data class SessionInfoDto(
        @get:JsonProperty("Id") val id: String,
        @get:JsonProperty("UserId") val userId: String,
        @get:JsonProperty("DeviceId") val deviceId: String,
        @get:JsonProperty("DeviceName") val deviceName: String,
        @get:JsonProperty("LastActivityDate") val lastActivityDate: String,
        @get:JsonProperty("NowPlayingItem") val nowPlayingItem: SessionNowPlayingItemDto?,
        @get:JsonProperty("PlayState") val playState: SessionPlayStateDto,
    )

    @GetMapping
    fun getSessions(principal: Principal): ResponseEntity<List<SessionInfoDto>> {
        val uid = UserId(principal.name)
        val sessions =
            deviceSessionProjection.getByUser(uid).map { s ->
                val nowPlaying = nowPlayingResolver.resolve(uid, s.sessionId)
                SessionInfoDto(
                    id = s.sessionId.value,
                    userId = uid.value,
                    deviceId = s.deviceId.value,
                    deviceName = s.deviceName ?: "Unknown Device",
                    lastActivityDate = s.lastSeenAt.toString(),
                    nowPlayingItem =
                        nowPlaying.nowPlayingItemId?.let { itemId ->
                            SessionNowPlayingItemDto(id = itemId, name = nowPlaying.nowPlayingItemName)
                        },
                    playState =
                        SessionPlayStateDto(
                            positionTicks = nowPlaying.positionMs * TICKS_PER_MS,
                            isPaused = nowPlaying.playbackState != "PLAYING",
                        ),
                )
            }
        return ResponseEntity.ok(sessions)
    }

    @PostMapping("/Playing")
    fun reportPlaying(
        @RequestBody info: PlaybackStartInfo,
        principal: Principal,
    ): ResponseEntity<Void> {
        if (!isValidUuid(info.itemId)) return ResponseEntity.badRequest().build()
        playbackStarts.put("${principal.name}:${info.itemId}", clock.now())
        recordSignal(principal, info.itemId, SignalType.PLAY_START)
        recordResume(principal, info.itemId, info.positionTicks, ResumeSource.START, info.eventTime)
        reflectDevicePlayback(principal, info.itemId, info.positionTicks, dev.yaytsa.domain.playback.PlaybackState.PLAYING, info.eventTime)
        return ResponseEntity.noContent().build()
    }

    @PostMapping("/Playing/Progress")
    fun reportProgress(
        @RequestBody info: PlaybackProgressInfo,
        principal: Principal,
    ): ResponseEntity<Void> {
        if (!isValidUuid(info.itemId)) return ResponseEntity.badRequest().build()
        // A seek is an authoritative exact-set even while playing — a deliberate rewind must not be
        // clamped forward by the furthest-position-wins heartbeat rule. Pause is exact-set too.
        val source =
            when {
                info.eventName.equals("Seek", ignoreCase = true) -> ResumeSource.SEEK
                info.isPaused -> ResumeSource.PAUSE
                else -> ResumeSource.PROGRESS
            }
        recordResume(principal, info.itemId, info.positionTicks, source, info.eventTime)
        reflectDevicePlayback(
            principal,
            info.itemId,
            info.positionTicks,
            if (info.isPaused) dev.yaytsa.domain.playback.PlaybackState.PAUSED else dev.yaytsa.domain.playback.PlaybackState.PLAYING,
            info.eventTime,
        )
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
        recordResume(principal, info.itemId, info.positionTicks, ResumeSource.STOPPED, info.eventTime)

        // Retrieve actual start time from when reportPlaying was called
        val startKey = "${principal.name}:${info.itemId}"
        val startedAt = playbackStarts.getIfPresent(startKey) ?: now
        playbackStarts.invalidate(startKey)

        // Delegate scrobble decision to core-application
        val runTimeMs = libraryQueries.getTrack(EntityId(info.itemId))?.durationMs ?: 0L
        scrobbleService.recordScrobble(
            userId = uid,
            trackId = trackId,
            startedAt = startedAt,
            stoppedAt = now,
            positionMs = positionMs,
            runTimeMs = runTimeMs,
            // Provenance, not mere co-existence: a scrobble is "adaptive" only when the stopped track
            // was actually served from the active radio/adaptive queue, not whenever a session happens
            // to be open. Tagging by session-existence let hand-picked tracks pollute the adaptive skip
            // metric, making it un-actionable.
            source =
                adaptiveQuery
                    .findActiveSession(uid)
                    ?.takeIf { session -> adaptiveQuery.getQueueEntries(session.id).any { it.trackId == trackId } }
                    ?.let { SOURCE_ADAPTIVE },
            deviceId = authenticatedDeviceId(),
        )

        reflectDevicePlayback(principal, info.itemId, info.positionTicks, dev.yaytsa.domain.playback.PlaybackState.STOPPED, info.eventTime)
        return ResponseEntity.noContent().build()
    }

    // Mirrors device-local playback into the authoritative playback session (keyed by the
    // reporting device's session id) so MCP/Sessions/devices views see what the PWA plays.
    // Best-effort: a failure must never break the 204 report contract. Reflection is gated
    // to material changes so steady playback stays near the Variant B write budget instead
    // of writing on every heartbeat.
    private fun reflectDevicePlayback(
        principal: Principal,
        itemId: String,
        positionTicks: Long,
        state: dev.yaytsa.domain.playback.PlaybackState,
        clientEventTime: Long? = null,
    ) {
        val deviceIdValue = authenticatedDeviceId() ?: return
        val uid = UserId(principal.name)
        val eventTimeKey = "${principal.name}:$deviceIdValue"
        if (clientEventTime != null) {
            val lastReflected = lastReflectedEventTimes.getIfPresent(eventTimeKey)
            if (lastReflected != null && clientEventTime < lastReflected) return
        }
        val deviceId = dev.yaytsa.shared.DeviceId(deviceIdValue)
        val sid =
            dev.yaytsa.domain.playback
                .SessionId(deviceIdValue)
        val positionMs = positionTicks / TICKS_PER_MS
        repeat(2) {
            val now = clock.now()
            val current = playbackUseCases.getPlaybackState(uid, sid)
            if (!shouldReflect(current, itemId, positionMs, state, deviceId, now)) return
            val cmd =
                dev.yaytsa.domain.playback.ReflectExternalPlayback(
                    sessionId = sid,
                    deviceId = deviceId,
                    trackId = TrackId(itemId),
                    // queue_entries/current_entry_id columns are UUID-sized (varchar 36):
                    // the track id doubles as the reflected entry id.
                    entryId =
                        dev.yaytsa.domain.playback
                            .QueueEntryId(itemId),
                    position = Duration.ofMillis(positionMs),
                    state = state,
                    leaseDuration = REFLECT_LEASE_DURATION,
                )
            val ctx = ctxFactory.create(uid, current?.version ?: AggregateVersion.INITIAL)
            when (val result = playbackUseCases.execute(cmd, ctx)) {
                is dev.yaytsa.shared.CommandResult.Success -> {
                    if (clientEventTime != null) lastReflectedEventTimes.put(eventTimeKey, clientEventTime)
                    return
                }
                is dev.yaytsa.shared.CommandResult.Failed -> {
                    val failure = result.failure
                    val isConflict =
                        failure is dev.yaytsa.shared.Failure.Conflict ||
                            failure is dev.yaytsa.shared.Failure.StorageConflict
                    if (!isConflict) {
                        // Retry only helps a version race; any other failure (incl. Unauthorized) is
                        // terminal. Unauthorized is an expected lease outcome (silent); anything else is
                        // unexpected on best-effort reflection and worth a WARN.
                        if (failure !is dev.yaytsa.shared.Failure.Unauthorized) {
                            log.warn("ReflectExternalPlayback failed for user {} device {}: {}", uid.value, deviceIdValue, failure)
                        }
                        return
                    }
                    // A conflict is a benign lost race on best-effort reflection: retry once with
                    // a fresh version, and if the retry is also lost the next progress report
                    // re-reflects fresh state, so it self-corrects and never warrants a WARN.
                    log.debug("ReflectExternalPlayback conflict for user {} device {}, retrying: {}", uid.value, deviceIdValue, failure)
                }
            }
        }
    }

    private fun shouldReflect(
        current: dev.yaytsa.domain.playback.PlaybackSessionAggregate?,
        itemId: String,
        positionMs: Long,
        state: dev.yaytsa.domain.playback.PlaybackState,
        deviceId: dev.yaytsa.shared.DeviceId,
        now: Instant,
    ): Boolean {
        if (current == null) return true
        val currentTrackId =
            current.currentEntryId?.let { entryId ->
                current.queue
                    .firstOrNull { it.id == entryId }
                    ?.trackId
                    ?.value
            }
        if (currentTrackId != itemId) return true
        if (current.playbackState != state) return true
        val lease = current.lease
        if (lease == null || lease.owner != deviceId || Duration.between(now, lease.expiresAt) < REFLECT_LEASE_RENEW_THRESHOLD) return true
        val driftMs = current.computePosition(now).toMillis() - positionMs
        return driftMs > REFLECT_POSITION_TOLERANCE_MS || driftMs < -REFLECT_POSITION_TOLERANCE_MS
    }

    private fun recordResume(
        principal: Principal,
        itemId: String,
        positionTicks: Long,
        sourceEvent: String,
        clientEventTime: Long? = null,
    ) {
        try {
            val runTimeMs = libraryQueries.getTrack(EntityId(itemId))?.durationMs ?: 0L
            resumePositionService.record(
                userId = UserId(principal.name),
                itemId = itemId,
                positionMs = positionTicks / TICKS_PER_MS,
                runTimeMs = runTimeMs,
                sourceEvent = sourceEvent,
                requestTime = resolveEventTime(clientEventTime),
            )
        } catch (e: Exception) {
            log.warn("Resume position recording failed for item {}: {}", itemId, e.message)
        }
    }

    // The client stamps each playback event with its own wall clock so the durable row is ordered by
    // when the event happened, not when it reached the server. This lets the merge reject a delayed
    // beacon from a backgrounded tab whose older position would otherwise clobber a newer device's
    // resume point. Future-skewed client clocks are capped at server time so they cannot pin the row.
    private fun resolveEventTime(clientEventTime: Long?): Instant {
        val now = clock.now()
        if (clientEventTime == null) return now
        val client = Instant.ofEpochMilli(clientEventTime)
        return if (client.isBefore(now)) client else now
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
