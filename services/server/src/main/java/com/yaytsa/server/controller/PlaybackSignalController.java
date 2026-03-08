package com.yaytsa.server.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yaytsa.server.domain.service.ListeningSessionService;
import com.yaytsa.server.domain.service.SignalProcessingService;
import com.yaytsa.server.dto.request.SubmitPlaybackSignalRequest;
import com.yaytsa.server.dto.response.PlaybackSignalResponse;
import com.yaytsa.server.infrastructure.persistence.entity.PlaybackSignalEntity;
import com.yaytsa.server.infrastructure.security.AuthenticatedUser;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/v1/sessions/{sessionId}/signals")
public class PlaybackSignalController {

  private static final Logger log = LoggerFactory.getLogger(PlaybackSignalController.class);
  private static final int MAX_SIGNAL_RETRIES = 3;

  private final SignalProcessingService signalService;
  private final ListeningSessionService sessionService;
  private final ObjectMapper objectMapper;

  public PlaybackSignalController(
      SignalProcessingService signalService,
      ListeningSessionService sessionService,
      ObjectMapper objectMapper) {
    this.signalService = signalService;
    this.sessionService = sessionService;
    this.objectMapper = objectMapper;
  }

  @PostMapping
  public ResponseEntity<PlaybackSignalResponse> submitSignal(
      @PathVariable UUID sessionId,
      @RequestBody SubmitPlaybackSignalRequest request,
      @AuthenticationPrincipal AuthenticatedUser user) {
    verifyOwnership(sessionId, user);
    if (request.signalType() == null || request.signalType().isBlank())
      return ResponseEntity.badRequest().build();
    UUID trackId = parseUuid(request.trackId());
    if (trackId == null) return ResponseEntity.badRequest().build();
    UUID queueEntryId = request.queueEntryId() != null ? parseUuid(request.queueEntryId()) : null;

    for (int attempt = 0; attempt < MAX_SIGNAL_RETRIES; attempt++) {
      try {
        var result =
            signalService.processSignal(
                sessionId, request.signalType(), trackId, queueEntryId, request.context());
        return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(result));
      } catch (ObjectOptimisticLockingFailureException e) {
        log.warn(
            "Signal concurrent conflict attempt {}/{} for session {}: {}",
            attempt + 1,
            MAX_SIGNAL_RETRIES,
            sessionId,
            e.getMessage());
      }
    }
    log.error(
        "Signal processing failed after {} retries for session {}", MAX_SIGNAL_RETRIES, sessionId);
    return ResponseEntity.status(HttpStatus.CREATED).build();
  }

  private void verifyOwnership(UUID sessionId, AuthenticatedUser user) {
    UUID ownerId = sessionService.getSessionOwnerId(sessionId);
    if (!user.getUserEntity().isAdmin() && !ownerId.equals(user.getUserEntity().getId()))
      throw new org.springframework.security.access.AccessDeniedException("Access denied");
  }

  private PlaybackSignalResponse toResponse(SignalProcessingService.SignalResult result) {
    PlaybackSignalEntity signal = result.signal();
    Object parsedContext;
    try {
      parsedContext =
          signal.getContext() != null
              ? objectMapper.readValue(signal.getContext(), Object.class)
              : null;
    } catch (JsonProcessingException e) {
      parsedContext = signal.getContext();
    }
    return new PlaybackSignalResponse(
        signal.getId().toString(),
        signal.getSession().getId().toString(),
        signal.getSignalType(),
        signal.getItem().getId().toString(),
        signal.getQueueEntry() != null ? signal.getQueueEntry().getId().toString() : null,
        parsedContext,
        signal.getCreatedAt(),
        result.triggerFired());
  }

  private UUID parseUuid(String value) {
    if (value == null) return null;
    try {
      return UUID.fromString(value);
    } catch (IllegalArgumentException e) {
      return null;
    }
  }
}
