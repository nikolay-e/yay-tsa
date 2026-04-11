package com.yaytsa.server.controller;

import com.yaytsa.server.domain.service.AdaptiveQueueManager;
import com.yaytsa.server.domain.service.AdaptiveQueueService;
import com.yaytsa.server.domain.service.ListeningSessionService;
import com.yaytsa.server.domain.service.QueueSseService;
import com.yaytsa.server.dto.response.AdaptiveQueueEntryResponse;
import com.yaytsa.server.dto.response.AdaptiveQueueResponse;
import com.yaytsa.server.infrastructure.security.AuthenticatedUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/v1/sessions/{sessionId}/queue")
@Tag(name = "Adaptive Queue", description = "DJ queue management")
public class AdaptiveQueueController {

  private final AdaptiveQueueService queueService;
  private final AdaptiveQueueManager queueManager;
  private final ListeningSessionService sessionService;
  private final QueueSseService sseService;

  public AdaptiveQueueController(
      AdaptiveQueueService queueService,
      AdaptiveQueueManager queueManager,
      ListeningSessionService sessionService,
      QueueSseService sseService) {
    this.queueService = queueService;
    this.queueManager = queueManager;
    this.sessionService = sessionService;
    this.sseService = sseService;
  }

  @Operation(summary = "Get session queue")
  @ApiResponses(
      value = {
        @ApiResponse(responseCode = "200", description = "Queue returned"),
        @ApiResponse(responseCode = "401", description = "Unauthorized"),
        @ApiResponse(responseCode = "403", description = "Forbidden")
      })
  @GetMapping
  public ResponseEntity<AdaptiveQueueResponse> getQueue(
      @PathVariable UUID sessionId, @AuthenticationPrincipal AuthenticatedUser user) {
    sessionService.verifyOwnership(sessionId, user);
    return ResponseEntity.ok(toResponse(sessionId, queueManager.getQueue(sessionId)));
  }

  @Operation(summary = "Refresh session queue")
  @ApiResponses(
      value = {
        @ApiResponse(responseCode = "200", description = "Queue refreshed"),
        @ApiResponse(responseCode = "401", description = "Unauthorized"),
        @ApiResponse(responseCode = "403", description = "Forbidden")
      })
  @PostMapping("/refresh")
  public ResponseEntity<AdaptiveQueueResponse> refreshQueue(
      @PathVariable UUID sessionId, @AuthenticationPrincipal AuthenticatedUser user) {
    sessionService.verifyOwnership(sessionId, user);
    queueService.refreshQueue(sessionId);
    var response = toResponse(sessionId, queueManager.getQueue(sessionId));
    sseService.broadcast(sessionId, "queue_updated", response);
    return ResponseEntity.ok(response);
  }

  @Operation(summary = "Stream queue updates via SSE")
  @ApiResponses(
      value = {
        @ApiResponse(responseCode = "200", description = "SSE stream started"),
        @ApiResponse(
            responseCode = "401",
            description = "Unauthorized",
            content = @Content(mediaType = "application/json")),
        @ApiResponse(
            responseCode = "403",
            description = "Forbidden",
            content = @Content(mediaType = "application/json"))
      })
  @GetMapping(value = "/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  public SseEmitter streamQueueUpdates(
      @PathVariable UUID sessionId, @AuthenticationPrincipal AuthenticatedUser user) {
    sessionService.verifyOwnership(sessionId, user);
    return sseService.createEmitter(sessionId);
  }

  private AdaptiveQueueResponse toResponse(
      UUID sessionId, List<AdaptiveQueueManager.QueueTrackDto> tracks) {
    var entries = tracks.stream().map(this::toEntryResponse).toList();
    return new AdaptiveQueueResponse(sessionId.toString(), entries, entries.size());
  }

  private AdaptiveQueueEntryResponse toEntryResponse(AdaptiveQueueManager.QueueTrackDto track) {
    AdaptiveQueueEntryResponse.TrackFeaturesDto featuresDto = null;
    if (track.features() != null) {
      featuresDto =
          new AdaptiveQueueEntryResponse.TrackFeaturesDto(
              track.features().bpm(),
              track.features().energy(),
              track.features().valence(),
              track.features().arousal(),
              track.features().danceability());
    }
    return new AdaptiveQueueEntryResponse(
        track.trackId().toString(),
        track.trackId().toString(),
        track.name(),
        track.artistName(),
        track.albumName(),
        track.durationMs(),
        track.position(),
        track.addedReason(),
        track.intentLabel(),
        track.status(),
        featuresDto,
        0,
        null,
        null);
  }
}
