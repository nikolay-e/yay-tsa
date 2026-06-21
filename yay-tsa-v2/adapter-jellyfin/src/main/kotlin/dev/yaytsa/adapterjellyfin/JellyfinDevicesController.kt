package dev.yaytsa.adapterjellyfin

import dev.yaytsa.adaptershared.AdapterCommandContextFactory
import dev.yaytsa.adaptershared.HttpFailureTranslator
import dev.yaytsa.adaptershared.problemDetail
import dev.yaytsa.application.playback.DeviceSessionProjection
import dev.yaytsa.application.playback.PlaybackUseCases
import dev.yaytsa.application.shared.port.Clock
import dev.yaytsa.application.shared.port.RemoteCommandPort
import dev.yaytsa.domain.playback.Pause
import dev.yaytsa.domain.playback.Play
import dev.yaytsa.domain.playback.Seek
import dev.yaytsa.domain.playback.SessionId
import dev.yaytsa.domain.playback.SkipNext
import dev.yaytsa.domain.playback.SkipPrevious
import dev.yaytsa.domain.playback.TransferLease
import dev.yaytsa.shared.CommandResult
import dev.yaytsa.shared.DeviceId
import dev.yaytsa.shared.UserId
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.security.Principal
import java.time.Duration

@RestController
@RequestMapping("/v1/me/devices")
class JellyfinDevicesController(
    private val deviceSessionProjection: DeviceSessionProjection,
    private val playbackUseCases: PlaybackUseCases,
    private val deviceEventBroadcaster: DeviceEventBroadcaster,
    private val nowPlayingResolver: DeviceNowPlayingResolver,
    private val remoteCommandPort: RemoteCommandPort,
    private val clock: Clock,
    @Qualifier("jellyfinCommandContextFactory")
    private val commandContextFactory: AdapterCommandContextFactory,
) {
    private val log = org.slf4j.LoggerFactory.getLogger(javaClass)

    data class HeartbeatRequest(
        val deviceId: String? = null,
        val sessionId: String? = null,
    )

    data class CommandRequest(
        val command: String,
        val params: Map<String, Any?> = emptyMap(),
    )

    data class TransferRequest(
        val toDeviceId: String,
    )

    data class DeviceSessionDto(
        val sessionId: String,
        val deviceId: String,
        val userId: String,
        val lastSeenAt: String,
        val deviceName: String?,
        val nowPlayingItemId: String?,
        val nowPlayingItemName: String?,
        val positionMs: Long,
        val playbackState: String,
    )

    @GetMapping("/events", produces = [org.springframework.http.MediaType.TEXT_EVENT_STREAM_VALUE])
    fun events(principal: Principal): org.springframework.web.servlet.mvc.method.annotation.SseEmitter {
        // Per-user SSE stream. The DeviceSseNotificationBridge forwards playback-state
        // notifications here as `device_state_changed` events, so a second device's
        // now-playing updates live instead of only on the heartbeat poll.
        return deviceEventBroadcaster.subscribe(principal.name)
    }

    @GetMapping
    fun listDevices(principal: Principal): ResponseEntity<List<DeviceSessionDto>> {
        val uid = UserId(principal.name)
        val sessions =
            deviceSessionProjection.getByUser(uid).map { s ->
                val nowPlaying = nowPlayingResolver.resolve(uid, s.sessionId)
                DeviceSessionDto(
                    sessionId = s.sessionId.value,
                    deviceId = s.deviceId.value,
                    userId = s.userId.value,
                    lastSeenAt = s.lastSeenAt.toString(),
                    deviceName = s.deviceName ?: "Unknown Device",
                    nowPlayingItemId = nowPlaying.nowPlayingItemId,
                    nowPlayingItemName = nowPlaying.nowPlayingItemName,
                    positionMs = nowPlaying.positionMs,
                    playbackState = nowPlaying.playbackState,
                )
            }
        return ResponseEntity.ok(sessions)
    }

    @PostMapping("/heartbeat")
    fun heartbeat(
        @RequestBody(required = false) request: HeartbeatRequest?,
        principal: Principal,
    ): ResponseEntity<Any> {
        val uid = UserId(principal.name)
        val now = clock.now()
        val auth =
            org.springframework.security.core.context.SecurityContextHolder
                .getContext()
                .authentication as? DeviceBoundAuthentication
        val authDeviceId = auth?.deviceId
        val deviceIdValue =
            request?.deviceId?.takeIf { it.isNotBlank() }
                ?: authDeviceId
                ?: return problemDetail(HttpStatus.BAD_REQUEST, "Bad Request", "deviceId required (missing in body and auth context)")
        val sessionIdValue =
            request?.sessionId?.takeIf { it.isNotBlank() }
                ?: deviceIdValue
        deviceSessionProjection.register(uid, SessionId(sessionIdValue), DeviceId(deviceIdValue), now, auth?.deviceName)
        return ResponseEntity.noContent().build()
    }

    @PostMapping("/{sessionId}/command")
    fun sendCommand(
        @PathVariable sessionId: String,
        @RequestBody request: CommandRequest,
        principal: Principal,
    ): ResponseEntity<Any> {
        val uid = UserId(principal.name)
        val sid = SessionId(sessionId)
        val current =
            playbackUseCases.getPlaybackState(uid, sid)
                ?: return problemDetail(HttpStatus.NOT_FOUND, "Not Found", "Session not found")
        val leaseOwner =
            current.lease?.owner
                ?: return problemDetail(HttpStatus.CONFLICT, "Conflict", "No active lease on session")

        val cmd =
            when (request.command.lowercase()) {
                "play" -> Play(sessionId = sid, deviceId = leaseOwner)
                "pause" -> Pause(sessionId = sid, deviceId = leaseOwner)
                "skip_next" -> SkipNext(sessionId = sid, deviceId = leaseOwner)
                "skip_previous" -> SkipPrevious(sessionId = sid, deviceId = leaseOwner)
                "seek" -> {
                    val positionMs = (request.params["positionMs"] as? Number)?.toLong() ?: 0L
                    Seek(sessionId = sid, deviceId = leaseOwner, position = Duration.ofMillis(positionMs))
                }
                else ->
                    return problemDetail(HttpStatus.BAD_REQUEST, "Bad Request", "Unknown command: ${request.command}")
            }

        val ctx = commandContextFactory.create(uid, current.version)
        return when (val result = playbackUseCases.execute(cmd, ctx)) {
            is CommandResult.Success -> {
                remoteCommandPort.publish(
                    userId = uid.value,
                    targetDeviceId = leaseOwner.value,
                    command = remoteCommandType(request.command),
                    params = request.params,
                )
                ResponseEntity.ok(
                    mapOf(
                        "sessionId" to sid.value,
                        "version" to result.newVersion.value,
                        "command" to request.command,
                    ),
                )
            }
            is CommandResult.Failed -> failureTranslator.translate(result.failure)
        }
    }

    private fun remoteCommandType(command: String): String =
        when (command.lowercase()) {
            "play" -> "PLAY"
            "pause" -> "PAUSE"
            "skip_next" -> "NEXT"
            "skip_previous" -> "PREV"
            "seek" -> "SEEK"
            else -> command.uppercase()
        }

    @PostMapping("/{sessionId}/transfer")
    fun transferLease(
        @PathVariable sessionId: String,
        @RequestBody request: TransferRequest,
        principal: Principal,
    ): ResponseEntity<Any> {
        log.info("Transfer lease requested for session {} to device {}", sessionId, request.toDeviceId)
        val uid = UserId(principal.name)
        val sid = SessionId(sessionId)
        if (request.toDeviceId.isBlank()) {
            return problemDetail(HttpStatus.BAD_REQUEST, "Bad Request", "toDeviceId required")
        }
        val current =
            playbackUseCases.getPlaybackState(uid, sid)
                ?: return problemDetail(HttpStatus.NOT_FOUND, "Not Found", "Session not found")
        val currentOwner =
            current.lease?.owner
                ?: return problemDetail(HttpStatus.CONFLICT, "Conflict", "No active lease on session")

        val cmd =
            TransferLease(
                sessionId = sid,
                fromDeviceId = currentOwner,
                toDeviceId = DeviceId(request.toDeviceId),
                leaseDuration = LEASE_DURATION,
            )
        val ctx = commandContextFactory.create(uid, current.version)
        return when (val result = playbackUseCases.execute(cmd, ctx)) {
            is CommandResult.Success -> {
                val updated = result.value
                remoteCommandPort.publish(
                    userId = uid.value,
                    targetDeviceId = currentOwner.value,
                    command = "STOP",
                )
                ResponseEntity.ok(
                    mapOf(
                        "sessionId" to sid.value,
                        "version" to result.newVersion.value,
                        "deviceId" to (updated.lease?.owner?.value),
                        "currentEntryId" to updated.currentEntryId?.value,
                        "positionMs" to updated.computePosition(ctx.requestTime).toMillis(),
                        "playbackState" to updated.playbackState.name,
                    ),
                )
            }
            is CommandResult.Failed -> failureTranslator.translate(result.failure)
        }
    }

    companion object {
        private val LEASE_DURATION: Duration = Duration.ofSeconds(60)
        private val failureTranslator = HttpFailureTranslator()
    }
}
