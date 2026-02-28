package com.yaytsa.server.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yaytsa.server.domain.service.ListeningSessionService;
import com.yaytsa.server.dto.request.CreateListeningSessionRequest;
import com.yaytsa.server.dto.request.UpdateSessionStateRequest;
import com.yaytsa.server.dto.response.ListeningSessionResponse;
import com.yaytsa.server.infrastructure.persistence.entity.ListeningSessionEntity;
import com.yaytsa.server.infrastructure.security.AuthenticatedUser;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/v1/sessions")
public class ListeningSessionController {

  private final ListeningSessionService sessionService;
  private final ObjectMapper objectMapper;

  public ListeningSessionController(
      ListeningSessionService sessionService, ObjectMapper objectMapper) {
    this.sessionService = sessionService;
    this.objectMapper = objectMapper;
  }

  @PostMapping
  public ResponseEntity<ListeningSessionResponse> createSession(
      @RequestBody(required = false) CreateListeningSessionRequest request,
      @AuthenticationPrincipal AuthenticatedUser user) {
    UUID userId = user.getUserEntity().getId();
    var session = sessionService.createSession(userId, request != null ? request.state() : null);
    return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(session));
  }

  @PatchMapping("/{id}/state")
  public ResponseEntity<ListeningSessionResponse> updateState(
      @PathVariable UUID id,
      @RequestBody UpdateSessionStateRequest request,
      @AuthenticationPrincipal AuthenticatedUser user) {
    verifyOwnership(id, user);
    return ResponseEntity.ok(toResponse(sessionService.updateState(id, request.state())));
  }

  @DeleteMapping("/{id}")
  public ResponseEntity<ListeningSessionResponse> endSession(
      @PathVariable UUID id, @AuthenticationPrincipal AuthenticatedUser user) {
    verifyOwnership(id, user);
    return ResponseEntity.ok(toResponse(sessionService.endSession(id)));
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
        entity.getSessionSummary());
  }
}
