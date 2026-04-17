package com.yaytsa.server.controller;

import com.yaytsa.server.domain.service.DeviceSseService;
import com.yaytsa.server.domain.service.SessionService;
import com.yaytsa.server.dto.request.PlaybackProgressInfo;
import com.yaytsa.server.dto.request.PlaybackStartInfo;
import com.yaytsa.server.dto.request.PlaybackStopInfo;
import com.yaytsa.server.infrastructure.persistence.entity.SessionEntity;
import com.yaytsa.server.infrastructure.security.AuthenticatedUser;
import com.yaytsa.server.util.UuidUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/Sessions")
@Tag(name = "Sessions", description = "Playback session management")
public class SessionsController {

  private static final long TICKS_PER_SECOND = 10_000_000L;

  private final SessionService sessionService;
  private final DeviceSseService deviceSseService;

  public SessionsController(SessionService sessionService, DeviceSseService deviceSseService) {
    this.sessionService = sessionService;
    this.deviceSseService = deviceSseService;
  }

  @Operation(
      summary = "Report playback started",
      description = "Notify server that playback has started for an item")
  @ApiResponses(
      value = {
        @ApiResponse(responseCode = "204", description = "Successfully reported"),
        @ApiResponse(responseCode = "401", description = "Unauthorized")
      })
  @PostMapping("/Playing")
  public ResponseEntity<Void> reportPlaybackStart(
      @RequestBody PlaybackStartInfo playbackInfo,
      @AuthenticationPrincipal AuthenticatedUser authenticatedUser) {

    UUID itemId = UuidUtils.parseUuid(playbackInfo.getItemId());
    if (itemId == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid item ID");
    }

    UUID userId = authenticatedUser.getUserEntity().getId();
    String deviceId = authenticatedUser.getDeviceId();
    String deviceName = authenticatedUser.getDeviceName();

    sessionService.reportPlaybackStart(userId, deviceId, deviceName, itemId);
    broadcastDeviceState(userId, deviceId, itemId, 0L, false);

    return ResponseEntity.noContent().build();
  }

  @Operation(summary = "Report playback progress", description = "Update current playback position")
  @ApiResponses(
      value = {
        @ApiResponse(responseCode = "204", description = "Successfully reported"),
        @ApiResponse(responseCode = "401", description = "Unauthorized")
      })
  @PostMapping("/Playing/Progress")
  public ResponseEntity<Void> reportPlaybackProgress(
      @RequestBody PlaybackProgressInfo progressInfo,
      @AuthenticationPrincipal AuthenticatedUser authenticatedUser) {

    UUID itemId = UuidUtils.parseUuid(progressInfo.getItemId());
    if (itemId == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid item ID");
    }

    UUID userId = authenticatedUser.getUserEntity().getId();
    String deviceId = authenticatedUser.getDeviceId();
    String deviceName = authenticatedUser.getDeviceName();
    Long ticks = progressInfo.getPositionTicks();
    long positionMs = ticks != null ? Math.max(0, ticks / (TICKS_PER_SECOND / 1000)) : 0;
    boolean isPaused = progressInfo.getIsPaused() != null && progressInfo.getIsPaused();

    sessionService.reportPlaybackProgress(
        userId, deviceId, deviceName, itemId, positionMs, isPaused);
    broadcastDeviceState(userId, deviceId, itemId, positionMs, isPaused);

    return ResponseEntity.noContent().build();
  }

  @Operation(
      summary = "Report playback stopped",
      description = "Notify server that playback has stopped")
  @ApiResponses(
      value = {
        @ApiResponse(responseCode = "204", description = "Successfully reported"),
        @ApiResponse(responseCode = "401", description = "Unauthorized")
      })
  @PostMapping("/Playing/Stopped")
  public ResponseEntity<Void> reportPlaybackStopped(
      @RequestBody PlaybackStopInfo stopInfo,
      @AuthenticationPrincipal AuthenticatedUser authenticatedUser) {

    UUID itemId = UuidUtils.parseUuid(stopInfo.getItemId());
    if (itemId == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid item ID");
    }

    UUID userId = authenticatedUser.getUserEntity().getId();
    String deviceId = authenticatedUser.getDeviceId();
    Long ticks = stopInfo.getPositionTicks();
    long positionMs = ticks != null ? Math.max(0, ticks / (TICKS_PER_SECOND / 1000)) : 0;

    sessionService.reportPlaybackStopped(userId, deviceId, itemId, positionMs);
    broadcastDeviceState(userId, deviceId, null, positionMs, true);

    return ResponseEntity.noContent().build();
  }

  @Operation(summary = "Get active sessions", description = "Retrieve all active playback sessions")
  @ApiResponses(
      value = {
        @ApiResponse(responseCode = "200", description = "Sessions returned"),
        @ApiResponse(responseCode = "401", description = "Unauthorized")
      })
  @GetMapping
  public ResponseEntity<List<Map<String, Object>>> getSessions(
      @AuthenticationPrincipal AuthenticatedUser authenticatedUser,
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @RequestParam(value = "api_key", required = false) String apiKey) {

    List<SessionEntity> activeSessions = sessionService.getAllActiveSessions();

    UUID currentUserId = authenticatedUser.getUserEntity().getId();
    boolean isAdmin = authenticatedUser.getUserEntity().isAdmin();

    List<Map<String, Object>> sessions =
        activeSessions.stream()
            .filter(s -> isAdmin || s.getUser().getId().equals(currentUserId))
            .map(this::mapSessionToResponse)
            .collect(Collectors.toList());

    return ResponseEntity.ok(sessions);
  }

  @Operation(summary = "Get session by ID", description = "Retrieve a specific playback session")
  @ApiResponses(
      value = {
        @ApiResponse(responseCode = "200", description = "Session returned"),
        @ApiResponse(responseCode = "400", description = "Invalid session ID"),
        @ApiResponse(responseCode = "401", description = "Unauthorized"),
        @ApiResponse(responseCode = "403", description = "Forbidden"),
        @ApiResponse(responseCode = "404", description = "Session not found")
      })
  @GetMapping("/{sessionId}")
  public ResponseEntity<Map<String, Object>> getSession(
      @PathVariable String sessionId,
      @AuthenticationPrincipal AuthenticatedUser authenticatedUser,
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @RequestParam(value = "api_key", required = false) String apiKey) {

    UUID sessionUuid = UuidUtils.parseUuid(sessionId);
    if (sessionUuid == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid session ID");
    }

    Optional<SessionEntity> sessionOpt = sessionService.getSession(sessionUuid);

    if (sessionOpt.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Session not found");
    }

    SessionEntity session = sessionOpt.get();
    UUID currentUserId = authenticatedUser.getUserEntity().getId();
    boolean isAdmin = authenticatedUser.getUserEntity().isAdmin();

    if (!isAdmin && !session.getUser().getId().equals(currentUserId)) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Access denied");
    }

    return ResponseEntity.ok(mapSessionToResponse(session));
  }

  private Map<String, Object> mapSessionToResponse(SessionEntity session) {
    Map<String, Object> response = new HashMap<>();
    response.put("Id", session.getId().toString());
    response.put("UserId", session.getUser().getId().toString());
    response.put("UserName", session.getUser().getUsername());
    response.put("DeviceId", session.getDeviceId());
    response.put("DeviceName", session.getDeviceName());
    response.put("LastActivityDate", session.getLastUpdate().toString());

    if (session.getNowPlayingItem() != null) {
      Map<String, Object> nowPlaying = new HashMap<>();
      nowPlaying.put("Id", session.getNowPlayingItem().getId().toString());
      nowPlaying.put("Name", session.getNowPlayingItem().getName());
      nowPlaying.put("Type", session.getNowPlayingItem().getType().name());
      response.put("NowPlayingItem", nowPlaying);
      response.put(
          "PlayState",
          Map.of(
              "PositionTicks",
              session.getPositionMs() * TICKS_PER_SECOND / 1000,
              "IsPaused",
              session.getPaused() != null && session.getPaused()));
    }

    return response;
  }

  @Operation(summary = "Keep session alive", description = "Ping to keep playback session active")
  @ApiResponses(
      value = {
        @ApiResponse(responseCode = "204", description = "Successfully pinged"),
        @ApiResponse(responseCode = "401", description = "Unauthorized")
      })
  @PostMapping("/Playing/Ping")
  public ResponseEntity<Void> pingSession(
      @AuthenticationPrincipal AuthenticatedUser authenticatedUser,
      @RequestParam(value = "playSessionId", required = false) String playSessionId) {

    UUID userId = authenticatedUser.getUserEntity().getId();
    String deviceId = authenticatedUser.getDeviceId();
    sessionService.pingSession(userId, deviceId);

    return ResponseEntity.noContent().build();
  }

  private void broadcastDeviceState(
      UUID userId, String deviceId, UUID itemId, long positionMs, boolean isPaused) {
    var state = new HashMap<String, Object>();
    state.put("deviceId", deviceId);
    if (itemId != null) state.put("nowPlayingItemId", itemId.toString());
    state.put("positionMs", positionMs);
    state.put("isPaused", isPaused);
    state.put("timestamp", System.currentTimeMillis());
    // sessionId resolved lazily by client via deviceId match from GET /devices
    deviceSseService.broadcastToUser(userId, "device_state_changed", state);
  }
}
