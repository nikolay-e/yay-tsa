package com.yaytsa.server.controller;

import com.yaytsa.server.domain.service.DevicePresenceService;
import com.yaytsa.server.domain.service.DeviceSseService;
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
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/v1/me")
@Tag(name = "Devices", description = "Device presence and remote control")
public class DeviceController {

  private final DevicePresenceService presenceService;
  private final DeviceSseService deviceSseService;

  public DeviceController(
      DevicePresenceService presenceService, DeviceSseService deviceSseService) {
    this.presenceService = presenceService;
    this.deviceSseService = deviceSseService;
  }

  @Operation(summary = "List my devices")
  @GetMapping("/devices")
  public ResponseEntity<List<Map<String, Object>>> getDevices(
      @AuthenticationPrincipal AuthenticatedUser user) {
    return ResponseEntity.ok(presenceService.listDevices(user.getUserEntity().getId()));
  }

  @Operation(summary = "Stream device state updates")
  @GetMapping(value = "/devices/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  public SseEmitter streamDeviceEvents(@AuthenticationPrincipal AuthenticatedUser user) {
    return deviceSseService.createDeviceListEmitter(user.getUserEntity().getId());
  }

  @Operation(summary = "Send heartbeat for this device")
  @PostMapping("/devices/heartbeat")
  public ResponseEntity<Void> heartbeat(@AuthenticationPrincipal AuthenticatedUser user) {
    presenceService.heartbeat(user.getUserEntity().getId(), user.getDeviceId());
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
    boolean sent =
        presenceService.sendCommand(deviceSessionId, user.getUserEntity().getId(), command);
    if (!sent) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    }
    return ResponseEntity.accepted().build();
  }

  @Operation(summary = "Subscribe to incoming commands for this device")
  @GetMapping(value = "/devices/commands", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  public SseEmitter streamCommands(@AuthenticationPrincipal AuthenticatedUser user) {
    String deviceKey = DeviceSseService.deviceKey(user.getUserEntity().getId(), user.getDeviceId());
    SseEmitter emitter = deviceSseService.createCommandEmitter(deviceKey);
    presenceService.drainPendingCommands(deviceKey, deviceSseService);
    return emitter;
  }

  @Operation(summary = "Transfer playback to this device")
  @ApiResponses(
      value = {
        @ApiResponse(responseCode = "200", description = "Transfer initiated"),
        @ApiResponse(responseCode = "403", description = "Not your device"),
        @ApiResponse(responseCode = "404", description = "Source device not found")
      })
  @PostMapping("/devices/{sourceSessionId}/transfer")
  public ResponseEntity<Map<String, Object>> transferPlayback(
      @PathVariable UUID sourceSessionId, @AuthenticationPrincipal AuthenticatedUser user) {
    var payload =
        presenceService.buildTransferPayload(sourceSessionId, user.getUserEntity().getId());
    if (payload == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    }
    return ResponseEntity.ok(payload);
  }
}
