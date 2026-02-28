package com.yaytsa.server.controller;

import com.yaytsa.server.domain.service.AdaptiveQueueManager;
import com.yaytsa.server.domain.service.AdaptiveQueueService;
import com.yaytsa.server.domain.service.ListeningSessionService;
import com.yaytsa.server.domain.service.QueueSseService;
import com.yaytsa.server.dto.response.AdaptiveQueueEntryResponse;
import com.yaytsa.server.dto.response.AdaptiveQueueResponse;
import com.yaytsa.server.infrastructure.security.AuthenticatedUser;
import java.util.List;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/v1/sessions/{sessionId}/queue")
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

  @GetMapping
  public ResponseEntity<AdaptiveQueueResponse> getQueue(
      @PathVariable UUID sessionId, @AuthenticationPrincipal AuthenticatedUser authenticatedUser) {

    verifyOwnership(sessionId, authenticatedUser);

    List<AdaptiveQueueManager.QueueTrackDto> tracks = queueManager.getQueue(sessionId);

    return ResponseEntity.ok(toResponse(sessionId, tracks));
  }

  @PostMapping("/refresh")
  public ResponseEntity<AdaptiveQueueResponse> refreshQueue(
      @PathVariable UUID sessionId, @AuthenticationPrincipal AuthenticatedUser authenticatedUser) {

    verifyOwnership(sessionId, authenticatedUser);

    queueService.refreshQueue(sessionId);

    List<AdaptiveQueueManager.QueueTrackDto> tracks = queueManager.getQueue(sessionId);
    AdaptiveQueueResponse response = toResponse(sessionId, tracks);

    sseService.broadcast(sessionId, "queue_updated", response);

    return ResponseEntity.ok(response);
  }

  @GetMapping(value = "/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  public SseEmitter streamQueueUpdates(
      @PathVariable UUID sessionId, @AuthenticationPrincipal AuthenticatedUser authenticatedUser) {

    verifyOwnership(sessionId, authenticatedUser);

    return sseService.createEmitter(sessionId);
  }

  private void verifyOwnership(UUID sessionId, AuthenticatedUser user) {
    UUID ownerId = sessionService.getSessionOwnerId(sessionId);
    UUID currentUserId = user.getUserEntity().getId();
    boolean isAdmin = user.getUserEntity().isAdmin();

    if (!isAdmin && !ownerId.equals(currentUserId)) {
      throw new org.springframework.security.access.AccessDeniedException("Access denied");
    }
  }

  private AdaptiveQueueResponse toResponse(
      UUID sessionId, List<AdaptiveQueueManager.QueueTrackDto> tracks) {
    List<AdaptiveQueueEntryResponse> entryResponses =
        tracks.stream().map(this::toEntryResponse).toList();

    return new AdaptiveQueueResponse(sessionId.toString(), entryResponses, entryResponses.size());
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
