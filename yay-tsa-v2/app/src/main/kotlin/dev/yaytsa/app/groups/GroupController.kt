package dev.yaytsa.app.groups

import com.fasterxml.jackson.annotation.JsonAlias
import com.fasterxml.jackson.annotation.JsonProperty
import dev.yaytsa.adapterjellyfin.DeviceBoundAuthentication
import dev.yaytsa.adaptershared.problemDetail
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.security.Principal
import java.util.UUID

@RestController
@RequestMapping("/v1/groups")
class GroupController(
    private val service: GroupSyncService,
    private val broadcaster: GroupEventBroadcaster,
) {
    data class CreateGroupRequest(
        val name: String,
        val trackId: String? = null,
    )

    data class JoinGroupRequest(
        val joinCode: String,
    )

    data class ScheduleRequest(
        @JsonProperty("expected_epoch")
        @JsonAlias("expectedEpoch")
        val expectedEpoch: Long,
        val action: String,
        val trackId: String? = null,
        val positionMs: Long? = null,
        val paused: Boolean? = null,
    )

    data class HeartbeatRequest(
        val rttMs: Int? = null,
    )

    private fun deviceId(): String? = (SecurityContextHolder.getContext().authentication as? DeviceBoundAuthentication)?.deviceId

    @PostMapping
    fun create(
        @RequestBody request: CreateGroupRequest,
        principal: Principal,
    ): ResponseEntity<Any> {
        if (request.name.isBlank()) return problemDetail(HttpStatus.BAD_REQUEST, "Bad Request", "name is required")
        val device = deviceId() ?: return problemDetail(HttpStatus.BAD_REQUEST, "Bad Request", "device context required")
        val result = service.createGroup(UUID.fromString(principal.name), device, request.name, request.trackId)
        return ResponseEntity.ok(mapOf("id" to result.id, "joinCode" to result.joinCode))
    }

    @PostMapping("/join")
    fun join(
        @RequestBody request: JoinGroupRequest,
        principal: Principal,
    ): ResponseEntity<Any> {
        val device = deviceId() ?: return problemDetail(HttpStatus.BAD_REQUEST, "Bad Request", "device context required")
        val snapshot =
            service.joinGroup(UUID.fromString(principal.name), device, request.joinCode)
                ?: return problemDetail(HttpStatus.NOT_FOUND, "Not Found", "join code not found")
        broadcaster.emit(UUID.fromString(snapshot.id), "member_joined", mapOf("deviceId" to device, "userId" to principal.name))
        return ResponseEntity.ok(snapshot)
    }

    private fun memberAccessFailure(
        groupId: UUID,
        principal: Principal,
    ): ResponseEntity<Any>? {
        if (!service.groupExists(groupId)) return ResponseEntity.status(404).build()
        if (!service.isMember(groupId, UUID.fromString(principal.name))) return ResponseEntity.status(HttpStatus.FORBIDDEN).build()
        return null
    }

    private fun ownerAccessFailure(
        groupId: UUID,
        principal: Principal,
    ): ResponseEntity<Any>? {
        if (!service.groupExists(groupId)) return ResponseEntity.status(404).build()
        if (!service.isOwner(groupId, UUID.fromString(principal.name))) return ResponseEntity.status(HttpStatus.FORBIDDEN).build()
        return null
    }

    @GetMapping("/{groupId}")
    fun snapshot(
        @PathVariable groupId: String,
        principal: Principal,
    ): ResponseEntity<Any> {
        val gid = UUID.fromString(groupId)
        memberAccessFailure(gid, principal)?.let { return it }
        val snapshot = service.snapshot(gid) ?: return ResponseEntity.status(404).build()
        return ResponseEntity.ok(snapshot)
    }

    @GetMapping("/{groupId}/events")
    fun events(
        @PathVariable groupId: String,
        principal: Principal,
    ): SseEmitter {
        val gid = UUID.fromString(groupId)
        if (!service.groupExists(gid)) throw ResponseStatusException(HttpStatus.NOT_FOUND)
        if (!service.isMember(gid, UUID.fromString(principal.name))) throw ResponseStatusException(HttpStatus.FORBIDDEN)
        return broadcaster.subscribe(gid)
    }

    @PostMapping("/{groupId}/schedule")
    fun schedule(
        @PathVariable groupId: String,
        @RequestBody request: ScheduleRequest,
        principal: Principal,
    ): ResponseEntity<Any> {
        val gid = UUID.fromString(groupId)
        ownerAccessFailure(gid, principal)?.let { return it }
        return when (
            val outcome =
                service.updateSchedule(
                    gid,
                    request.expectedEpoch,
                    request.action,
                    request.trackId,
                    request.positionMs,
                    request.paused,
                )
        ) {
            is ScheduleOutcome.Updated -> {
                broadcaster.emit(gid, "schedule_changed", outcome.response.schedule)
                ResponseEntity.ok(outcome.response)
            }
            ScheduleOutcome.Conflict -> problemDetail(HttpStatus.CONFLICT, "Conflict", "schedule epoch conflict")
            ScheduleOutcome.NotFound -> ResponseEntity.status(404).build()
        }
    }

    @PostMapping("/{groupId}/heartbeat")
    fun heartbeat(
        @PathVariable groupId: String,
        @RequestBody(required = false) request: HeartbeatRequest?,
        principal: Principal,
    ): ResponseEntity<Any> {
        val gid = UUID.fromString(groupId)
        memberAccessFailure(gid, principal)?.let { return it }
        val device = deviceId() ?: return problemDetail(HttpStatus.BAD_REQUEST, "Bad Request", "device context required")
        service.heartbeat(gid, UUID.fromString(principal.name), device, request?.rttMs)
        return ResponseEntity.ok().build()
    }

    @DeleteMapping("/{groupId}/members/{deviceId}")
    fun leave(
        @PathVariable groupId: String,
        @PathVariable deviceId: String,
        principal: Principal,
    ): ResponseEntity<Any> {
        val gid = UUID.fromString(groupId)
        memberAccessFailure(gid, principal)?.let { return it }
        val callerId = UUID.fromString(principal.name)
        val deviceOwner = service.memberDeviceOwner(gid, deviceId)
        if (deviceOwner != callerId && !service.isOwner(gid, callerId)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build()
        }
        service.leave(gid, deviceId)
        broadcaster.emit(gid, "member_left", mapOf("deviceId" to deviceId))
        return ResponseEntity.ok().build()
    }

    @DeleteMapping("/{groupId}")
    fun end(
        @PathVariable groupId: String,
        principal: Principal,
    ): ResponseEntity<Any> {
        val gid = UUID.fromString(groupId)
        ownerAccessFailure(gid, principal)?.let { return it }
        service.end(gid)
        broadcaster.emit(gid, "group_ended", mapOf<String, Any>())
        return ResponseEntity.ok().build()
    }
}
