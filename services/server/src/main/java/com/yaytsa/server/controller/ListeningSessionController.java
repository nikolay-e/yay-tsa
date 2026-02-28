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
@RequestMapping("/api/v1/sessions")
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
      @AuthenticationPrincipal AuthenticatedUser authenticatedUser) {

    UUID userId = authenticatedUser.getUserEntity().getId();
    var state = request != null ? request.state() : null;

    ListeningSessionEntity session = sessionService.createSession(userId, state);

    return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(session));
  }

  @PatchMapping("/{id}/state")
  public ResponseEntity<ListeningSessionResponse> updateState(
      @PathVariable UUID id,
      @RequestBody UpdateSessionStateRequest request,
      @AuthenticationPrincipal AuthenticatedUser authenticatedUser) {

    verifyOwnership(id, authenticatedUser);

    ListeningSessionEntity session = sessionService.updateState(id, request.state());

    return ResponseEntity.ok(toResponse(session));
  }

  @DeleteMapping("/{id}")
  public ResponseEntity<ListeningSessionResponse> endSession(
      @PathVariable UUID id, @AuthenticationPrincipal AuthenticatedUser authenticatedUser) {

    verifyOwnership(id, authenticatedUser);

    ListeningSessionEntity session = sessionService.endSession(id);

    return ResponseEntity.ok(toResponse(session));
  }

  @GetMapping("/{id}")
  public ResponseEntity<ListeningSessionResponse> getSession(
      @PathVariable UUID id, @AuthenticationPrincipal AuthenticatedUser authenticatedUser) {

    verifyOwnership(id, authenticatedUser);

    ListeningSessionEntity session = sessionService.getSession(id);

    return ResponseEntity.ok(toResponse(session));
  }

  private void verifyOwnership(UUID sessionId, AuthenticatedUser user) {
    UUID ownerId = sessionService.getSessionOwnerId(sessionId);
    UUID currentUserId = user.getUserEntity().getId();
    boolean isAdmin = user.getUserEntity().isAdmin();

    if (!isAdmin && !ownerId.equals(currentUserId)) {
      throw new org.springframework.security.access.AccessDeniedException("Access denied");
    }
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
