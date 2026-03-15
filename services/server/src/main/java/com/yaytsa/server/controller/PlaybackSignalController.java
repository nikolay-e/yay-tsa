package com.yaytsa.server.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yaytsa.server.domain.service.AdaptiveQueueService;
import com.yaytsa.server.domain.service.ListeningSessionService;
import com.yaytsa.server.domain.service.SignalProcessingService;
import com.yaytsa.server.dto.request.SubmitPlaybackSignalRequest;
import com.yaytsa.server.dto.response.PlaybackSignalResponse;
import com.yaytsa.server.infrastructure.persistence.entity.PlaybackSignalEntity;
import com.yaytsa.server.infrastructure.security.AuthenticatedUser;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
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

  private final ConcurrentHashMap<UUID, ReentrantLock> sessionLocks = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<UUID, Long> lastSignalMs = new ConcurrentHashMap<>();

  private final SignalProcessingService signalService;
  private final ListeningSessionService sessionService;
  private final AdaptiveQueueService adaptiveQueueService;
  private final ObjectMapper objectMapper;

  public PlaybackSignalController(
      SignalProcessingService signalService,
      ListeningSessionService sessionService,
      AdaptiveQueueService adaptiveQueueService,
      ObjectMapper objectMapper) {
    this.signalService = signalService;
    this.sessionService = sessionService;
    this.adaptiveQueueService = adaptiveQueueService;
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

    long now = System.currentTimeMillis();
    if (now - lastSignalMs.getOrDefault(sessionId, 0L) < 100)
      return ResponseEntity.status(429).build();
    lastSignalMs.put(sessionId, now);

    ReentrantLock lock = sessionLocks.computeIfAbsent(sessionId, id -> new ReentrantLock());
    lock.lock();
    try {
      var result =
          signalService.processSignal(
              sessionId, request.signalType(), trackId, queueEntryId, request.context());
      if (result.triggerFired()) {
        Thread.ofVirtual()
            .start(
                () ->
                    adaptiveQueueService.triggerDjDecision(
                        result.sessionId(), result.triggerType()));
      }
      return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(result));
    } catch (ObjectOptimisticLockingFailureException e) {
      log.error(
          "Unexpected optimistic lock conflict for session {}: {}", sessionId, e.getMessage());
      return ResponseEntity.status(HttpStatus.CREATED).build();
    } finally {
      lock.unlock();
    }
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
