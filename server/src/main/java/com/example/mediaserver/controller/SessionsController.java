package com.example.mediaserver.controller;

import com.example.mediaserver.domain.service.SessionService;
import com.example.mediaserver.dto.request.PlaybackProgressInfo;
import com.example.mediaserver.dto.request.PlaybackStartInfo;
import com.example.mediaserver.dto.request.PlaybackStopInfo;
import com.example.mediaserver.infra.security.AuthenticatedUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * Controller for managing playback sessions.
 * Handles play state reporting and session tracking.
 */
@RestController
@RequestMapping("/Sessions")
@Tag(name = "Sessions", description = "Playback session management")
public class SessionsController {

    private static final long TICKS_PER_SECOND = 10_000_000L;

    private final SessionService sessionService;

    public SessionsController(SessionService sessionService) {
        this.sessionService = sessionService;
    }

    @Operation(summary = "Report playback started",
              description = "Notify server that playback has started for an item")
    @ApiResponses(value = {
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

    @Operation(summary = "Report playback progress",
              description = "Update current playback position")
    @ApiResponses(value = {
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
        long positionMs = progressInfo.getPositionTicks() / (TICKS_PER_SECOND / 1000);
        boolean isPaused = progressInfo.getIsPaused() != null && progressInfo.getIsPaused();

        sessionService.reportPlaybackProgress(userId, deviceId, itemId, positionMs, isPaused);

        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Report playback stopped",
              description = "Notify server that playback has stopped")
    @ApiResponses(value = {
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
        long positionMs = stopInfo.getPositionTicks() / (TICKS_PER_SECOND / 1000);

        sessionService.reportPlaybackStopped(userId, deviceId, itemId, positionMs);

        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Get active sessions",
              description = "Retrieve all active playback sessions")
    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> getSessions(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestParam(value = "api_key", required = false) String apiKey) {

        // TODO: Implement in Phase 6
        List<Map<String, Object>> sessions = new ArrayList<>();
        return ResponseEntity.ok(sessions);
    }

    @Operation(summary = "Get session by ID",
              description = "Retrieve a specific playback session")
    @GetMapping("/{sessionId}")
    public ResponseEntity<Map<String, Object>> getSession(
            @PathVariable String sessionId,
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestParam(value = "api_key", required = false) String apiKey) {

        // TODO: Implement in Phase 6
        Map<String, Object> session = new HashMap<>();
        session.put("Id", sessionId);
        session.put("UserId", "user-id");
        session.put("DeviceId", "device-id");
        session.put("NowPlayingItem", null);

        return ResponseEntity.ok(session);
    }

    @Operation(summary = "Keep session alive",
              description = "Ping to keep playback session active")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "204", description = "Successfully pinged"),
        @ApiResponse(responseCode = "401", description = "Unauthorized")
    })
    @PostMapping("/Playing/Ping")
    public ResponseEntity<Void> pingSession(
            @RequestParam(value = "playSessionId", required = false) String playSessionId) {

        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Send playback command",
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
