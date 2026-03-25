package com.yaytsa.server.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yaytsa.server.domain.service.AdaptiveQueueService;
import com.yaytsa.server.domain.service.ListeningSessionService;
import com.yaytsa.server.dto.request.CreateListeningSessionRequest;
import com.yaytsa.server.dto.request.UpdateSessionStateRequest;
import com.yaytsa.server.dto.response.ListeningSessionResponse;
import com.yaytsa.server.infrastructure.persistence.entity.ListeningSessionEntity;
import com.yaytsa.server.infrastructure.persistence.repository.TrackFeaturesRepository;
import com.yaytsa.server.infrastructure.security.AuthenticatedUser;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/v1/sessions")
public class ListeningSessionController {

  private final ListeningSessionService sessionService;
  private final AdaptiveQueueService adaptiveQueueService;
  private final TrackFeaturesRepository trackFeaturesRepository;
  private final ObjectMapper objectMapper;
  private final Executor applicationTaskExecutor;

  public ListeningSessionController(
      ListeningSessionService sessionService,
      AdaptiveQueueService adaptiveQueueService,
      TrackFeaturesRepository trackFeaturesRepository,
      ObjectMapper objectMapper,
      @Qualifier("applicationTaskExecutor") Executor applicationTaskExecutor) {
    this.sessionService = sessionService;
    this.adaptiveQueueService = adaptiveQueueService;
    this.trackFeaturesRepository = trackFeaturesRepository;
    this.objectMapper = objectMapper;
    this.applicationTaskExecutor = applicationTaskExecutor;
  }

  @PostMapping
  public ResponseEntity<ListeningSessionResponse> createSession(
      @RequestBody(required = false) CreateListeningSessionRequest request,
      @AuthenticationPrincipal AuthenticatedUser user) {
    UUID userId = user.getUserEntity().getId();
    UUID seedTrackId = request != null ? request.seedTrackId() : null;

    if (seedTrackId != null) {
      var features = trackFeaturesRepository.findByTrackId(seedTrackId).orElse(null);
      if (features == null || features.getEmbeddingMert() == null) {
        throw new ResponseStatusException(
            HttpStatus.BAD_REQUEST, "Seed track has no MERT embedding");
      }
    }

    var session =
        sessionService.createSession(userId, request != null ? request.state() : null, seedTrackId);
    return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(session));
  }

  @PatchMapping("/{id}/state")
  public ResponseEntity<ListeningSessionResponse> updateState(
      @PathVariable UUID id,
      @RequestBody UpdateSessionStateRequest request,
      @AuthenticationPrincipal AuthenticatedUser user) {
    verifyOwnership(id, user);
    var session = sessionService.updateState(id, request.state());
    CompletableFuture.runAsync(
        () -> adaptiveQueueService.triggerDjDecision(session, "MOOD_CHANGE", null),
        applicationTaskExecutor);
    return ResponseEntity.ok(toResponse(session));
  }

  @DeleteMapping("/{id}")
  public ResponseEntity<ListeningSessionResponse> endSession(
      @PathVariable UUID id, @AuthenticationPrincipal AuthenticatedUser user) {
    verifyOwnership(id, user);
    return ResponseEntity.ok(toResponse(sessionService.endSession(id)));
  }

  @GetMapping("/active")
  public ResponseEntity<ListeningSessionResponse> getActiveSession(
      @AuthenticationPrincipal AuthenticatedUser user) {
    UUID userId = user.getUserEntity().getId();
    var session = sessionService.findActiveSession(userId);
    if (session == null) {
      return ResponseEntity.noContent().build();
    }
    return ResponseEntity.ok(toResponse(session));
  }

  @GetMapping("/{id}")
  public ResponseEntity<ListeningSessionResponse> getSession(
      @PathVariable UUID id, @AuthenticationPrincipal AuthenticatedUser user) {
    verifyOwnership(id, user);
    return ResponseEntity.ok(toResponse(sessionService.getSession(id)));
  }

  private void verifyOwnership(UUID sessionId, AuthenticatedUser user) {
    UUID ownerId = sessionService.getSessionOwnerId(sessionId);
    if (!user.getUserEntity().isAdmin() && !ownerId.equals(user.getUserEntity().getId()))
      throw new org.springframework.security.access.AccessDeniedException("Access denied");
  }

  private ListeningSessionResponse toResponse(ListeningSessionEntity entity) {
    Object parsedState;
    try {
      parsedState = objectMapper.readValue(entity.getState(), Object.class);
    } catch (JsonProcessingException e) {
      parsedState = entity.getState();
    }
    return new ListeningSessionResponse(
        entity.getId().toString(),
        entity.getUser().getId().toString(),
        parsedState,
        entity.getStartedAt(),
        entity.getLastActivityAt(),
        entity.getEndedAt(),
        entity.getSessionSummary(),
        entity.isRadioMode(),
        entity.getSeedTrackId() != null ? entity.getSeedTrackId().toString() : null);
  }
}
