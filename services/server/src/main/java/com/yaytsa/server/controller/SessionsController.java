package com.yaytsa.server.controller;

import com.yaytsa.server.domain.service.SessionService;
import com.yaytsa.server.dto.request.PlaybackProgressInfo;
import com.yaytsa.server.dto.request.PlaybackStartInfo;
import com.yaytsa.server.dto.request.PlaybackStopInfo;
import com.yaytsa.server.infrastructure.persistence.entity.SessionEntity;
import com.yaytsa.server.infrastructure.security.AuthenticatedUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.*;
import java.util.stream.Collectors;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/** Controller for managing playback sessions. Handles play state reporting and session tracking. */
@RestController
@RequestMapping("/Sessions")
@Tag(name = "Sessions", description = "Playback session management")
public class SessionsController {

  private static final long TICKS_PER_SECOND = 10_000_000L;

  private final SessionService sessionService;

  public SessionsController(SessionService sessionService) {
    this.sessionService = sessionService;
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

    UUID userId = authenticatedUser.getUserEntity().getId();
    String deviceId = authenticatedUser.getDeviceId();
    UUID itemId = UUID.fromString(playbackInfo.getItemId());

    sessionService.reportPlaybackStart(userId, deviceId, itemId);

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

    UUID userId = authenticatedUser.getUserEntity().getId();
    String deviceId = authenticatedUser.getDeviceId();
    UUID itemId = UUID.fromString(progressInfo.getItemId());
    Long ticks = progressInfo.getPositionTicks();
    long positionMs = ticks != null ? Math.max(0, ticks / (TICKS_PER_SECOND / 1000)) : 0;
    boolean isPaused = progressInfo.getIsPaused() != null && progressInfo.getIsPaused();

    sessionService.reportPlaybackProgress(userId, deviceId, itemId, positionMs, isPaused);

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

    UUID userId = authenticatedUser.getUserEntity().getId();
    String deviceId = authenticatedUser.getDeviceId();
    UUID itemId = UUID.fromString(stopInfo.getItemId());
    Long ticks = stopInfo.getPositionTicks();
    long positionMs = ticks != null ? Math.max(0, ticks / (TICKS_PER_SECOND / 1000)) : 0;

    sessionService.reportPlaybackStopped(userId, deviceId, itemId, positionMs);

    return ResponseEntity.noContent().build();
  }

  @Operation(summary = "Get active sessions", description = "Retrieve all active playback sessions")
  @GetMapping
  public ResponseEntity<List<Map<String, Object>>> getSessions(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @RequestParam(value = "api_key", required = false) String apiKey) {

    List<SessionEntity> activeSessions = sessionService.getAllActiveSessions();

    List<Map<String, Object>> sessions =
        activeSessions.stream().map(this::mapSessionToResponse).collect(Collectors.toList());

    return ResponseEntity.ok(sessions);
  }

  @Operation(summary = "Get session by ID", description = "Retrieve a specific playback session")
  @GetMapping("/{sessionId}")
  public ResponseEntity<Map<String, Object>> getSession(
      @PathVariable String sessionId,
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @RequestParam(value = "api_key", required = false) String apiKey) {

    Optional<SessionEntity> sessionOpt = sessionService.getSession(UUID.fromString(sessionId));

    if (sessionOpt.isEmpty()) {
      return ResponseEntity.notFound().build();
    }

    return ResponseEntity.ok(mapSessionToResponse(sessionOpt.get()));
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
      @RequestParam(value = "playSessionId", required = false) String playSessionId) {

    return ResponseEntity.noContent().build();
  }

  @Operation(
      summary = "Send playback command",
      description = "Send a command to control playback (play, pause, stop, next, previous)")
  @PostMapping("/{sessionId}/Playing/{command}")
  public ResponseEntity<Void> sendPlaybackCommand(
      @PathVariable String sessionId,
      @PathVariable String command,
      @RequestBody(required = false) Map<String, Object> commandData,
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @RequestParam(value = "api_key", required = false) String apiKey) {

    // TODO: Implement remote control commands
    return ResponseEntity.noContent().build();
  }
}
