package com.example.mediaserver.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
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

    @Operation(summary = "Report playback started",
              description = "Notify server that playback has started for an item")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "204", description = "Successfully reported"),
        @ApiResponse(responseCode = "401", description = "Unauthorized")
    })
    @PostMapping("/Playing")
    public ResponseEntity<Void> reportPlaybackStart(
            @RequestBody Map<String, Object> playbackInfo,
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestParam(value = "api_key", required = false) String apiKey) {

        // TODO: Implement in Phase 6
        // Expected fields: ItemId, PositionTicks, IsPaused, PlayMethod, PlaySessionId
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
            @RequestBody Map<String, Object> progressInfo,
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestParam(value = "api_key", required = false) String apiKey) {

        // TODO: Implement in Phase 6
        // Expected fields: ItemId, PositionTicks, IsPaused, PlayMethod, PlaySessionId
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
            @RequestBody Map<String, Object> stopInfo,
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestParam(value = "api_key", required = false) String apiKey) {

        // TODO: Implement in Phase 6
        // Expected fields: ItemId, PositionTicks, PlaySessionId
        // Should update play count if played > 50% or > 240 seconds
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
