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
import com.yaytsa.server.util.UuidUtils;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/v1/sessions/{sessionId}/signals")
@Slf4j
public class PlaybackSignalController {

  private final SignalProcessingService signalService;
  private final ListeningSessionService sessionService;
  private final AdaptiveQueueService adaptiveQueueService;
  private final Executor signalAsyncExecutor;
  private final ObjectMapper objectMapper;

  public PlaybackSignalController(
      SignalProcessingService signalService,
      ListeningSessionService sessionService,
      AdaptiveQueueService adaptiveQueueService,
      @Qualifier("signalAsyncExecutor") Executor signalAsyncExecutor,
      ObjectMapper objectMapper) {
    this.signalService = signalService;
    this.sessionService = sessionService;
    this.adaptiveQueueService = adaptiveQueueService;
    this.signalAsyncExecutor = signalAsyncExecutor;
    this.objectMapper = objectMapper;
  }

  @PostMapping
  public ResponseEntity<PlaybackSignalResponse> submitSignal(
      @PathVariable UUID sessionId,
      @RequestBody SubmitPlaybackSignalRequest request,
      @AuthenticationPrincipal AuthenticatedUser user) {
    sessionService.verifyOwnership(sessionId, user);
    if (request.signalType() == null || request.signalType().isBlank())
      return ResponseEntity.badRequest().build();
    UUID trackId = UuidUtils.parseUuid(request.trackId());
    if (trackId == null) return ResponseEntity.badRequest().build();
    UUID queueEntryId =
        request.queueEntryId() != null ? UuidUtils.parseUuid(request.queueEntryId()) : null;

    try {
      var result =
          signalService.processSignal(
              sessionId, request.signalType(), trackId, queueEntryId, request.context());
      if (result.triggerFired()) {
        CompletableFuture.runAsync(
            () -> adaptiveQueueService.triggerDjDecision(result.sessionId(), result.triggerType()),
            signalAsyncExecutor);
      }
      return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(result));
    } catch (ObjectOptimisticLockingFailureException e) {
      log.error(
          "Unexpected optimistic lock conflict for session {}: {}", sessionId, e.getMessage());
      return ResponseEntity.status(HttpStatus.CREATED).build();
    }
  }

  private PlaybackSignalResponse toResponse(SignalProcessingService.SignalResult result) {
    PlaybackSignalEntity signal = result.persist().signal();
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
}
