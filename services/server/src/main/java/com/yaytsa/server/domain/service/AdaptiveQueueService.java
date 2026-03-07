package com.yaytsa.server.domain.service;

import com.yaytsa.server.error.ResourceNotFoundException;
import com.yaytsa.server.error.ResourceType;
import com.yaytsa.server.infrastructure.persistence.entity.AdaptiveQueueEntity;
import com.yaytsa.server.infrastructure.persistence.entity.ListeningSessionEntity;
import com.yaytsa.server.infrastructure.persistence.entity.UserPreferenceContractEntity;
import com.yaytsa.server.infrastructure.persistence.repository.AdaptiveQueueRepository;
import com.yaytsa.server.infrastructure.persistence.repository.ListeningSessionRepository;
import com.yaytsa.server.infrastructure.persistence.repository.UserPreferenceContractRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class AdaptiveQueueService {

  private static final Logger log = LoggerFactory.getLogger(AdaptiveQueueService.class);

  private final AdaptiveQueueRepository queueRepository;
  private final ListeningSessionRepository sessionRepository;
  private final UserPreferenceContractRepository contractRepository;
  private final LlmDjService llmDjService;
  private final QueuePolicyValidator policyValidator;
  private final AdaptiveQueueManager queueManager;
  private final FallbackQueueService fallbackQueueService;

  public AdaptiveQueueService(
      AdaptiveQueueRepository queueRepository,
      ListeningSessionRepository sessionRepository,
      UserPreferenceContractRepository contractRepository,
      LlmDjService llmDjService,
      QueuePolicyValidator policyValidator,
      AdaptiveQueueManager queueManager,
      FallbackQueueService fallbackQueueService) {
    this.queueRepository = queueRepository;
    this.sessionRepository = sessionRepository;
    this.contractRepository = contractRepository;
    this.llmDjService = llmDjService;
    this.policyValidator = policyValidator;
    this.queueManager = queueManager;
    this.fallbackQueueService = fallbackQueueService;
  }

  public List<AdaptiveQueueEntity> getQueue(UUID sessionId) {
    validateSessionExists(sessionId);
    return queueRepository.findBySessionIdAndStatusInOrderByPositionAsc(
        sessionId, List.of("QUEUED", "PLAYING"));
  }

  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  public List<AdaptiveQueueEntity> refreshQueue(UUID sessionId) {
    ListeningSessionEntity session =
        sessionRepository
            .findById(sessionId)
            .orElseThrow(
                () -> new ResourceNotFoundException(ResourceType.ListeningSession, sessionId));

    log.info("Manual DJ refresh triggered for session {}", sessionId);

    triggerDjDecision(session, "MANUAL_REFRESH", null);

    return queueRepository.findBySessionIdAndStatusInOrderByPositionAsc(
        sessionId, List.of("QUEUED", "PLAYING"));
  }

  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  public void triggerDjDecision(
      ListeningSessionEntity session,
      String triggerType,
      com.yaytsa.server.infrastructure.persistence.entity.PlaybackSignalEntity triggerSignal) {

    Optional<DjDecision> decision =
        llmDjService.generateDecision(session, triggerType, triggerSignal);

    List<AdaptiveQueueEntity> currentQueue =
        queueRepository.findBySessionIdAndStatusInOrderByPositionAsc(
            session.getId(), List.of("QUEUED", "PLAYING"));

    if (decision.isEmpty()) {
      log.info("LLM unavailable, using fallback for session {}", session.getId());
      fillQueueWithRetry(session, currentQueue.size());
      return;
    }

    UserPreferenceContractEntity preferences =
        contractRepository.findById(session.getUser().getId()).orElse(null);

    QueuePolicyValidator.ValidationResult validation =
        policyValidator.validate(decision.get(), currentQueue, preferences, session);

    if ("NO_EDITS".equals(validation.outcome())) {
      log.warn(
          "LLM returned no edits for session {} despite instructions, using fallback",
          session.getId());
      fillQueueWithRetry(session, currentQueue.size());
      return;
    }

    if ("REJECTED".equals(validation.outcome())) {
      log.warn(
          "All {} LLM edits rejected by policy for session {}, using fallback",
          decision.get().edits().size(),
          session.getId());
      fillQueueWithRetry(session, currentQueue.size());
      return;
    }

    try {
      var result =
          queueManager.applyDecision(
              session.getId(), decision.get().baseQueueVersion(), validation);
      if (!result.success()) {
        log.info(
            "LLM decision failed for session {}: {}, retrying with fresh state",
            session.getId(),
            result.error());
        retryOrFallback(session, decision.get(), preferences);
      }
    } catch (org.springframework.orm.ObjectOptimisticLockingFailureException e) {
      log.info(
          "Optimistic lock conflict for session {}, retrying with fresh state", session.getId());
      retryOrFallback(session, decision.get(), preferences);
    }
  }

  private void retryOrFallback(
      ListeningSessionEntity session,
      DjDecision decision,
      UserPreferenceContractEntity preferences) {
    try {
      List<AdaptiveQueueEntity> freshQueue =
          queueRepository.findBySessionIdAndStatusInOrderByPositionAsc(
              session.getId(), List.of("QUEUED", "PLAYING"));
      long freshVersion = queueManager.getCurrentVersion(session.getId());

      QueuePolicyValidator.ValidationResult freshValidation =
          policyValidator.validate(decision, freshQueue, preferences, session);

      if ("REJECTED".equals(freshValidation.outcome())
          || "NO_EDITS".equals(freshValidation.outcome())) {
        log.info(
            "Retry validation outcome {} for session {}, falling back",
            freshValidation.outcome(),
            session.getId());
        fillQueueWithRetry(session, freshQueue.size());
        return;
      }

      var retryResult = queueManager.applyDecision(session.getId(), freshVersion, freshValidation);
      if (!retryResult.success()) {
        log.info(
            "Retry also failed for session {}: {}, falling back",
            session.getId(),
            retryResult.error());
        fillQueueWithRetry(session, freshQueue.size());
      }
    } catch (Exception e) {
      log.warn("Retry failed with exception for session {}, falling back", session.getId(), e);
      fillQueueWithRetry(session, 0);
    }
  }

  private List<AdaptiveQueueEntity> fillQueueWithRetry(
      ListeningSessionEntity session, int currentQueueSize) {
    for (int attempt = 0; attempt < 3; attempt++) {
      try {
        return fallbackQueueService.fillQueue(session, currentQueueSize);
      } catch (DataIntegrityViolationException e) {
        log.warn(
            "Queue fill version conflict attempt {} for session {}",
            attempt + 1,
            session.getId());
      }
    }
    log.error("Queue fill failed after 3 retries for session {}", session.getId());
    return List.of();
  }

  private void validateSessionExists(UUID sessionId) {
    if (!sessionRepository.existsById(sessionId)) {
      throw new ResourceNotFoundException(ResourceType.ListeningSession, sessionId);
    }
  }
}
