package com.yaytsa.server.controller;

import com.yaytsa.server.domain.service.PlaybackGroupService;
import com.yaytsa.server.domain.service.PlaybackGroupService.GroupSnapshot;
import com.yaytsa.server.domain.service.QueueSseService;
import com.yaytsa.server.infrastructure.persistence.entity.PlaybackGroupMemberEntity;
import com.yaytsa.server.infrastructure.security.AuthenticatedUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/v1/groups")
@Tag(name = "Playback Groups", description = "Multi-device synchronized playback")
public class PlaybackGroupController {

  private final PlaybackGroupService groupService;
  private final QueueSseService sseService;

  public PlaybackGroupController(PlaybackGroupService groupService, QueueSseService sseService) {
    this.groupService = groupService;
    this.sseService = sseService;
  }

  @Operation(summary = "Create a playback group")
  @ApiResponses(
      value = {
        @ApiResponse(responseCode = "201", description = "Group created"),
        @ApiResponse(responseCode = "400", description = "No active session"),
        @ApiResponse(responseCode = "401", description = "Unauthorized")
      })
  @PostMapping
  public ResponseEntity<Map<String, Object>> createGroup(
      @RequestBody CreateGroupRequest request, @AuthenticationPrincipal AuthenticatedUser user) {
    var group =
        groupService.createGroup(
            user.getUserEntity().getId(), user.getDeviceId(), request.name(), request.trackId());
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(
            Map.of(
                "id", group.getId().toString(),
                "joinCode", group.getJoinCode(),
                "name", group.getName() != null ? group.getName() : ""));
  }

  @Operation(summary = "Join a playback group")
  @ApiResponses(
      value = {
        @ApiResponse(responseCode = "200", description = "Joined group"),
        @ApiResponse(responseCode = "404", description = "Invalid join code")
      })
  @PostMapping("/join")
  public ResponseEntity<Map<String, Object>> joinGroup(
      @RequestBody JoinGroupRequest request, @AuthenticationPrincipal AuthenticatedUser user) {
    var group =
        groupService.joinGroup(
            request.joinCode(), user.getDeviceId(), user.getUserEntity().getId());
    var snapshot = groupService.getSnapshot(group.getId());
    UUID sessionId = group.getListeningSession().getId();
    sseService.broadcast(
        sessionId,
        "member_joined",
        Map.of(
            "groupId", group.getId().toString(),
            "deviceId", user.getDeviceId(),
            "userId", user.getUserEntity().getId().toString()));
    return ResponseEntity.ok(snapshotToMap(snapshot));
  }

  @Operation(summary = "Get group snapshot")
  @GetMapping("/{id}")
  public ResponseEntity<Map<String, Object>> getGroup(
      @PathVariable UUID id, @AuthenticationPrincipal AuthenticatedUser user) {
    groupService.verifyMembership(id, user.getDeviceId());
    var snapshot = groupService.getSnapshot(id);
    return ResponseEntity.ok(snapshotToMap(snapshot));
  }

  @Operation(summary = "Update playback schedule")
  @ApiResponses(
      value = {
        @ApiResponse(responseCode = "200", description = "Schedule updated"),
        @ApiResponse(responseCode = "409", description = "Epoch conflict")
      })
  @PostMapping("/{id}/schedule")
  public ResponseEntity<Map<String, Object>> updateSchedule(
      @PathVariable UUID id,
      @RequestBody ScheduleRequest request,
      @AuthenticationPrincipal AuthenticatedUser user) {

    int resumeBuffer = groupService.getAdaptiveResumeBufferMs(id);

    long anchorPositionMs;
    boolean isPaused;
    UUID trackId = request.trackId();

    switch (request.action()) {
      case "PAUSE" -> {
        isPaused = true;
        anchorPositionMs = request.positionMs() != null ? request.positionMs() : 0;
      }
      case "PLAY" -> {
        isPaused = false;
        anchorPositionMs = request.positionMs() != null ? request.positionMs() : 0;
      }
      case "SEEK" -> {
        isPaused = request.paused() != null && request.paused();
        anchorPositionMs = request.positionMs() != null ? request.positionMs() : 0;
      }
      default -> {
        // NEXT, PREV, JUMP — new track
        isPaused = false;
        anchorPositionMs = 0;
      }
    }

    var result =
        groupService.updateSchedule(
            id,
            user.getDeviceId(),
            request.expectedEpoch(),
            trackId,
            resumeBuffer,
            anchorPositionMs,
            isPaused,
            null,
            null);

    if (!result.success()) {
      return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "epoch_conflict"));
    }

    if (result.sessionId() != null && result.schedule() != null) {
      sseService.broadcast(
          result.sessionId(),
          "schedule_changed",
          PlaybackGroupService.scheduleToMap(result.schedule()));
    }

    return ResponseEntity.ok(
        Map.of(
            "scheduleEpoch", result.schedule().getScheduleEpoch(),
            "schedule", PlaybackGroupService.scheduleToMap(result.schedule()),
            "serverTimeMs", System.currentTimeMillis()));
  }

  @Operation(summary = "Send heartbeat")
  @ApiResponses(
      value = {
        @ApiResponse(responseCode = "204", description = "Heartbeat recorded"),
        @ApiResponse(responseCode = "404", description = "Not a member — rejoin or go solo")
      })
  @PostMapping("/{id}/heartbeat")
  public ResponseEntity<Void> heartbeat(
      @PathVariable UUID id,
      @RequestBody(required = false) HeartbeatRequest request,
      @AuthenticationPrincipal AuthenticatedUser user) {
    var result =
        groupService.heartbeat(id, user.getDeviceId(), request != null ? request.rttMs() : null);
    if (result == PlaybackGroupService.HeartbeatResult.NOT_FOUND) {
      return ResponseEntity.notFound().build();
    }
    if (result == PlaybackGroupService.HeartbeatResult.REJOINED) {
      UUID sessionId = groupService.getSessionIdForGroup(id);
      if (sessionId != null) {
        sseService.broadcast(
            sessionId,
            "member_rejoined",
            Map.of(
                "groupId", id.toString(),
                "deviceId", user.getDeviceId(),
                "userId", user.getUserEntity().getId().toString()));
      }
    }
    return ResponseEntity.noContent().build();
  }

  @Operation(summary = "Leave or kick from group")
  @DeleteMapping("/{id}/members/{deviceId}")
  public ResponseEntity<Void> leaveGroup(
      @PathVariable UUID id,
      @PathVariable String deviceId,
      @AuthenticationPrincipal AuthenticatedUser user) {
    UUID sessionId = groupService.getSessionIdForGroup(id);
    var leaveResult = groupService.leaveGroup(id, deviceId, user.getUserEntity().getId());
    if (sessionId != null) {
      sseService.broadcast(
          sessionId,
          "member_left",
          Map.of(
              "groupId", id.toString(),
              "deviceId", deviceId,
              "userId", user.getUserEntity().getId().toString()));
      if (leaveResult == PlaybackGroupService.LeaveResult.GROUP_ENDED) {
        sseService.broadcast(sessionId, "group_ended", id.toString());
      }
    }
    return ResponseEntity.noContent().build();
  }

  @Operation(summary = "End group (owner only)")
  @DeleteMapping("/{id}")
  public ResponseEntity<Void> endGroup(
      @PathVariable UUID id, @AuthenticationPrincipal AuthenticatedUser user) {
    UUID sessionId = groupService.getSessionIdForGroup(id);
    groupService.endGroup(id, user.getUserEntity().getId());
    if (sessionId != null) {
      sseService.broadcast(sessionId, "group_ended", id.toString());
    }
    return ResponseEntity.noContent().build();
  }

  @Operation(summary = "Stream group events via SSE")
  @GetMapping(value = "/{id}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  public SseEmitter streamEvents(
      @PathVariable UUID id,
      @AuthenticationPrincipal AuthenticatedUser user,
      @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId) {
    groupService.verifyMembership(id, user.getDeviceId());
    var snapshot = groupService.getSnapshot(id);
    UUID sessionId = snapshot.group().getListeningSession().getId();
    return sseService.createEmitter(sessionId, lastEventId);
  }

  private static Map<String, Object> snapshotToMap(GroupSnapshot snapshot) {
    var schedule = snapshot.schedule();
    var scheduleMap =
        Map.of(
            "trackId", schedule.getTrackId().toString(),
            "anchorServerMs", schedule.getAnchorServerMs(),
            "anchorPositionMs", schedule.getAnchorPositionMs(),
            "isPaused", schedule.isPaused(),
            "scheduleEpoch", schedule.getScheduleEpoch());

    List<Map<String, Object>> memberMaps =
        snapshot.members().stream().map(PlaybackGroupController::memberToMap).toList();

    return Map.of(
        "id",
        snapshot.group().getId().toString(),
        "ownerId",
        snapshot.group().getOwner().getId().toString(),
        "joinCode",
        snapshot.group().getJoinCode(),
        "name",
        snapshot.group().getName() != null ? snapshot.group().getName() : "",
        "schedule",
        scheduleMap,
        "members",
        memberMaps);
  }

  private static Map<String, Object> memberToMap(PlaybackGroupMemberEntity m) {
    return Map.of(
        "deviceId", m.getDeviceId(),
        "userId", m.getUserId().toString(),
        "stale", m.isStale(),
        "reportedLatencyMs", m.getReportedLatencyMs());
  }

  record CreateGroupRequest(String name, UUID trackId) {}

  record JoinGroupRequest(String joinCode) {}

  record ScheduleRequest(
      long expectedEpoch, String action, UUID trackId, Long positionMs, Boolean paused) {}

  record HeartbeatRequest(Integer rttMs, Integer clockOffsetMs) {}
}
