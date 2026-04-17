package com.yaytsa.server.controller;

import com.yaytsa.server.domain.service.DeviceSseService;
import com.yaytsa.server.infrastructure.persistence.entity.SessionEntity;
import com.yaytsa.server.infrastructure.persistence.repository.SessionRepository;
import com.yaytsa.server.infrastructure.security.AuthenticatedUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/v1/me")
@Tag(name = "Devices", description = "Device presence and remote control")
public class DeviceController {

  private static final long ONLINE_THRESHOLD_SECONDS = 30;

  private final SessionRepository sessionRepository;
  private final DeviceSseService deviceSseService;

  public DeviceController(SessionRepository sessionRepository, DeviceSseService deviceSseService) {
    this.sessionRepository = sessionRepository;
    this.deviceSseService = deviceSseService;
  }

  @Operation(summary = "List my devices")
  @GetMapping("/devices")
  public ResponseEntity<List<Map<String, Object>>> getDevices(
      @AuthenticationPrincipal AuthenticatedUser user) {
    UUID userId = user.getUserEntity().getId();
    var sessions = sessionRepository.findAllByUserIdWithItem(userId);
    return ResponseEntity.ok(sessions.stream().map(this::sessionToDeviceMap).toList());
  }

  @Operation(summary = "Stream device state updates")
  @GetMapping(value = "/devices/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  public SseEmitter streamDeviceEvents(@AuthenticationPrincipal AuthenticatedUser user) {
    return deviceSseService.createDeviceListEmitter(user.getUserEntity().getId());
  }

  @Operation(summary = "Send heartbeat for this device")
  @PostMapping("/devices/heartbeat")
  public ResponseEntity<Void> heartbeat(@AuthenticationPrincipal AuthenticatedUser user) {
    UUID userId = user.getUserEntity().getId();
    String deviceId = user.getDeviceId();
    sessionRepository
        .findByUserIdAndDeviceId(userId, deviceId)
        .ifPresent(
            session -> {
              session.setOnline(true);
              session.setLastHeartbeatAt(OffsetDateTime.now());
              sessionRepository.save(session);
            });
    return ResponseEntity.noContent().build();
  }

  @Operation(summary = "Send command to a device")
  @ApiResponses(
      value = {
        @ApiResponse(responseCode = "202", description = "Command accepted"),
        @ApiResponse(responseCode = "403", description = "Not your device"),
        @ApiResponse(responseCode = "404", description = "Device not found")
      })
  @PostMapping("/devices/{deviceSessionId}/command")
  public ResponseEntity<Void> sendCommand(
      @PathVariable UUID deviceSessionId,
      @RequestBody Map<String, Object> command,
      @AuthenticationPrincipal AuthenticatedUser user) {
    var session =
        sessionRepository
            .findById(deviceSessionId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));

    if (!session.getUser().getId().equals(user.getUserEntity().getId())) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Not your device");
    }

    String deviceKey = DeviceSseService.deviceKey(session.getUser().getId(), session.getDeviceId());
    deviceSseService.sendCommand(deviceKey, command);
    return ResponseEntity.accepted().build();
  }

  @Operation(summary = "Subscribe to incoming commands for this device")
  @GetMapping(value = "/devices/commands", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  public SseEmitter streamCommands(@AuthenticationPrincipal AuthenticatedUser user) {
    String deviceKey = DeviceSseService.deviceKey(user.getUserEntity().getId(), user.getDeviceId());
    return deviceSseService.createCommandEmitter(deviceKey);
  }

  @Operation(summary = "Transfer playback to this device")
  @ApiResponses(
      value = {
        @ApiResponse(responseCode = "200", description = "Transfer initiated"),
        @ApiResponse(responseCode = "404", description = "Source device not found")
      })
  @PostMapping("/devices/{sourceSessionId}/transfer")
  public ResponseEntity<Map<String, Object>> transferPlayback(
      @PathVariable UUID sourceSessionId, @AuthenticationPrincipal AuthenticatedUser user) {
    var source =
        sessionRepository
            .findByIdWithUserAndItem(sourceSessionId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));

    if (!source.getUser().getId().equals(user.getUserEntity().getId())) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Not your device");
    }

    Map<String, Object> transferPayload = new LinkedHashMap<>();
    if (source.getNowPlayingItem() != null) {
      transferPayload.put("trackId", source.getNowPlayingItem().getId().toString());
      transferPayload.put("trackName", source.getNowPlayingItem().getName());
    }
    transferPayload.put("positionMs", source.getPositionMs());
    transferPayload.put("paused", source.getPaused());
    transferPayload.put("volumeLevel", source.getVolumeLevel());

    String sourceKey = DeviceSseService.deviceKey(source.getUser().getId(), source.getDeviceId());
    deviceSseService.sendCommand(sourceKey, Map.of("type", "PAUSE"));

    return ResponseEntity.ok(transferPayload);
  }

  private Map<String, Object> sessionToDeviceMap(SessionEntity s) {
    var map = new LinkedHashMap<String, Object>();
    map.put("sessionId", s.getId().toString());
    map.put("deviceId", s.getDeviceId());
    map.put("deviceName", s.getDeviceName());
    map.put("clientName", s.getClientName());
    map.put("isOnline", s.isOnline());
    map.put("lastUpdate", s.getLastUpdate().toString());
    if (s.getNowPlayingItem() != null) {
      map.put("nowPlayingItemId", s.getNowPlayingItem().getId().toString());
      map.put("nowPlayingItemName", s.getNowPlayingItem().getName());
    }
    map.put("positionMs", s.getPositionMs());
    map.put("isPaused", s.getPaused());
    map.put("volumeLevel", s.getVolumeLevel());
    return map;
  }
}
