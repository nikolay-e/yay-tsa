package dev.yaytsa.adapterjellyfin

import dev.yaytsa.application.playback.DeviceSessionProjection
import dev.yaytsa.application.playback.PlaybackUseCases
import dev.yaytsa.application.shared.port.Clock
import dev.yaytsa.domain.playback.DeviceId
import dev.yaytsa.domain.playback.Pause
import dev.yaytsa.domain.playback.Play
import dev.yaytsa.domain.playback.Seek
import dev.yaytsa.domain.playback.SessionId
import dev.yaytsa.domain.playback.SkipNext
import dev.yaytsa.domain.playback.SkipPrevious
import dev.yaytsa.shared.CommandContext
import dev.yaytsa.shared.CommandResult
import dev.yaytsa.shared.IdempotencyKey
import dev.yaytsa.shared.ProtocolId
import dev.yaytsa.shared.UserId
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
import java.util.UUID

@RestController
@RequestMapping("/v1/me/devices")
class JellyfinDevicesController(
    private val deviceSessionProjection: DeviceSessionProjection,
    private val playbackUseCases: PlaybackUseCases,
    private val clock: Clock,
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
    )

    @GetMapping
    fun listDevices(principal: Principal): ResponseEntity<List<DeviceSessionDto>> {
        val uid = UserId(principal.name)
        val sessions =
            deviceSessionProjection.getByUser(uid).map { s ->
                DeviceSessionDto(
                    sessionId = s.sessionId.value,
                    deviceId = s.deviceId.value,
                    userId = s.userId.value,
                    lastSeenAt = s.lastSeenAt.toString(),
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
        val authDeviceId =
            (
                org.springframework.security.core.context.SecurityContextHolder
                    .getContext()
                    .authentication as? DeviceBoundAuthentication
            )?.deviceId
        val deviceIdValue =
            request?.deviceId?.takeIf { it.isNotBlank() }
                ?: authDeviceId
                ?: return ResponseEntity
                    .status(HttpStatus.BAD_REQUEST)
                    .body(mapOf("error" to "deviceId required (missing in body and auth context)"))
        val sessionIdValue =
            request?.sessionId?.takeIf { it.isNotBlank() }
                ?: deviceIdValue
        deviceSessionProjection.register(uid, SessionId(sessionIdValue), DeviceId(deviceIdValue), now)
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
                ?: return ResponseEntity.status(HttpStatus.NOT_FOUND).body(mapOf("error" to "Session not found"))
        val leaseOwner =
            current.lease?.owner
                ?: return ResponseEntity.status(HttpStatus.CONFLICT).body(mapOf("error" to "No active lease on session"))

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
                    return ResponseEntity
                        .status(HttpStatus.BAD_REQUEST)
                        .body(mapOf("error" to "Unknown command: ${request.command}"))
            }

        val ctx =
            CommandContext(
                uid,
                ProtocolId("JELLYFIN"),
                clock.now(),
                IdempotencyKey(UUID.randomUUID().toString()),
                current.version,
            )
        return when (val result = playbackUseCases.execute(cmd, ctx)) {
            is CommandResult.Success ->
                ResponseEntity.ok(
                    mapOf(
                        "sessionId" to sid.value,
                        "version" to result.newVersion.value,
                        "command" to request.command,
                    ),
                )
            is CommandResult.Failed ->
                ResponseEntity
                    .status(HttpStatus.CONFLICT)
                    .body(mapOf("error" to result.failure.toString()))
        }
    }

    @PostMapping("/{sessionId}/transfer")
    fun transferLease(
        @PathVariable sessionId: String,
        @RequestBody request: TransferRequest,
        principal: Principal,
    ): ResponseEntity<Any> {
        log.info("Transfer lease requested for session {} to device {}", sessionId, request.toDeviceId)
        // TODO: atomic lease transfer use case not yet implemented in core-application/playback.
        // Requires a TransferLease command + handler that releases the current lease and acquires
        // a new one for the target device in a single transaction.
        return ResponseEntity
            .status(HttpStatus.NOT_IMPLEMENTED)
            .body(
                mapOf(
                    "error" to "lease_transfer_not_implemented",
                    "sessionId" to sessionId,
                    "toDeviceId" to request.toDeviceId,
                ),
            )
    }
}
