package com.yaytsa.server.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yaytsa.server.domain.service.AdaptiveQueueService;
import com.yaytsa.server.domain.service.ListeningSessionService;
import com.yaytsa.server.dto.request.CreateListeningSessionRequest;
import com.yaytsa.server.dto.request.UpdateSessionStateRequest;
import com.yaytsa.server.dto.response.ListeningSessionResponse;
import com.yaytsa.server.infrastructure.persistence.entity.ListeningSessionEntity;
import com.yaytsa.server.infrastructure.security.AuthenticatedUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/v1/sessions")
@Tag(name = "Listening Sessions", description = "Adaptive DJ listening session management")
public class ListeningSessionController {

  private final ListeningSessionService sessionService;
  private final AdaptiveQueueService adaptiveQueueService;
  private final ObjectMapper objectMapper;
  private final Executor applicationTaskExecutor;

  public ListeningSessionController(
      ListeningSessionService sessionService,
      AdaptiveQueueService adaptiveQueueService,
      ObjectMapper objectMapper,
      @Qualifier("applicationTaskExecutor") Executor applicationTaskExecutor) {
    this.sessionService = sessionService;
    this.adaptiveQueueService = adaptiveQueueService;
    this.objectMapper = objectMapper;
    this.applicationTaskExecutor = applicationTaskExecutor;
  }

  @Operation(summary = "Create listening session")
  @ApiResponses(
      value = {
        @ApiResponse(responseCode = "201", description = "Session created"),
        @ApiResponse(responseCode = "401", description = "Unauthorized")
      })
  @PostMapping
  public ResponseEntity<ListeningSessionResponse> createSession(
      @RequestBody(required = false) CreateListeningSessionRequest request,
      @AuthenticationPrincipal AuthenticatedUser user) {
    UUID userId = user.getUserEntity().getId();
    UUID seedTrackId = request != null ? request.seedTrackId() : null;

    var session =
        sessionService.createSession(userId, request != null ? request.state() : null, seedTrackId);
    return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(session));
  }

  @Operation(summary = "Update session state")
  @ApiResponses(
      value = {
        @ApiResponse(responseCode = "200", description = "State updated"),
        @ApiResponse(responseCode = "401", description = "Unauthorized"),
        @ApiResponse(responseCode = "403", description = "Forbidden")
      })
  @PatchMapping("/{id}/state")
  public ResponseEntity<ListeningSessionResponse> updateState(
      @PathVariable UUID id,
      @RequestBody UpdateSessionStateRequest request,
      @AuthenticationPrincipal AuthenticatedUser user) {
    sessionService.verifyOwnership(id, user);
    var session = sessionService.updateState(id, request.state());
    CompletableFuture.runAsync(
        () -> adaptiveQueueService.triggerDjDecision(session, "MOOD_CHANGE", null),
        applicationTaskExecutor);
    return ResponseEntity.ok(toResponse(session));
  }

  @Operation(summary = "End listening session")
  @ApiResponses(
      value = {
        @ApiResponse(responseCode = "200", description = "Session ended"),
        @ApiResponse(responseCode = "401", description = "Unauthorized"),
        @ApiResponse(responseCode = "403", description = "Forbidden")
      })
  @DeleteMapping("/{id}")
  public ResponseEntity<ListeningSessionResponse> endSession(
      @PathVariable UUID id, @AuthenticationPrincipal AuthenticatedUser user) {
    sessionService.verifyOwnership(id, user);
    return ResponseEntity.ok(toResponse(sessionService.endSession(id)));
  }

  @Operation(summary = "Get active listening session")
  @ApiResponses(
      value = {
        @ApiResponse(responseCode = "200", description = "Active session found"),
        @ApiResponse(responseCode = "204", description = "No active session"),
        @ApiResponse(responseCode = "401", description = "Unauthorized")
      })
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

  @Operation(summary = "Get listening session by ID")
  @ApiResponses(
      value = {
        @ApiResponse(responseCode = "200", description = "Session found"),
        @ApiResponse(responseCode = "401", description = "Unauthorized"),
        @ApiResponse(responseCode = "403", description = "Forbidden")
      })
  @GetMapping("/{id}")
  public ResponseEntity<ListeningSessionResponse> getSession(
      @PathVariable UUID id, @AuthenticationPrincipal AuthenticatedUser user) {
    sessionService.verifyOwnership(id, user);
    return ResponseEntity.ok(toResponse(sessionService.getSession(id)));
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
