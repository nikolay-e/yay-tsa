package com.yaytsa.server.domain.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.yaytsa.server.domain.service.LlmSessionParamService.DjSessionParams;
import com.yaytsa.server.error.ResourceNotFoundException;
import com.yaytsa.server.error.ResourceType;
import com.yaytsa.server.infrastructure.persistence.entity.AdaptiveQueueEntity;
import com.yaytsa.server.infrastructure.persistence.entity.ListeningSessionEntity;
import com.yaytsa.server.infrastructure.persistence.entity.PlaybackSignalEntity;
import com.yaytsa.server.infrastructure.persistence.repository.AdaptiveQueueRepository;
import com.yaytsa.server.infrastructure.persistence.repository.ListeningSessionRepository;
import com.yaytsa.server.infrastructure.persistence.repository.UserPreferenceContractRepository;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class AdaptiveQueueService {

  private static final Logger log = LoggerFactory.getLogger(AdaptiveQueueService.class);

  private final long llmCooldownMs;

  private final Cache<UUID, Long> lastLlmAttemptMs;

  private final Cache<UUID, DjSessionParams> lastSessionParams =
      Caffeine.newBuilder().expireAfterWrite(30, TimeUnit.MINUTES).maximumSize(500).build();

  private final AdaptiveQueueRepository queueRepository;
  private final ListeningSessionRepository sessionRepository;
  private final UserPreferenceContractRepository contractRepository;
  private final LlmSessionParamService llmSessionParamService;
  private final FallbackQueueService fallbackQueueService;
  private final ObjectMapper objectMapper;

  public AdaptiveQueueService(
      AdaptiveQueueRepository queueRepository,
      ListeningSessionRepository sessionRepository,
      UserPreferenceContractRepository contractRepository,
      LlmSessionParamService llmSessionParamService,
      FallbackQueueService fallbackQueueService,
      ObjectMapper objectMapper,
      @Value("${yaytsa.adaptive-dj.llm-cooldown-ms:120000}") long llmCooldownMs) {
    this.queueRepository = queueRepository;
    this.sessionRepository = sessionRepository;
    this.contractRepository = contractRepository;
    this.llmSessionParamService = llmSessionParamService;
    this.fallbackQueueService = fallbackQueueService;
    this.objectMapper = objectMapper;
    this.llmCooldownMs = llmCooldownMs;
    this.lastLlmAttemptMs =
        Caffeine.newBuilder()
            .expireAfterWrite(llmCooldownMs + 10_000, TimeUnit.MILLISECONDS)
            .maximumSize(500)
            .build();
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
  public void triggerDjDecision(UUID sessionId, String triggerType) {
    ListeningSessionEntity session = sessionRepository.findById(sessionId).orElse(null);
    if (session == null) return;
    triggerDjDecision(session, triggerType, null);
  }

  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  public void triggerDjDecision(
      ListeningSessionEntity session, String triggerType, PlaybackSignalEntity triggerSignal) {

    List<AdaptiveQueueEntity> currentQueue =
        queueRepository.findBySessionIdAndStatusInOrderByPositionAsc(
            session.getId(), List.of("QUEUED", "PLAYING"));

    boolean manualRefresh = "MANUAL_REFRESH".equals(triggerType);
    long now = System.currentTimeMillis();
    Long lastAttempt = lastLlmAttemptMs.getIfPresent(session.getId());
    boolean coolingDown =
        !manualRefresh && lastAttempt != null && (now - lastAttempt) < llmCooldownMs;

    Optional<DjSessionParams> params = Optional.empty();
    int currentQueueSize = currentQueue.size();
    if (!coolingDown) {
      lastLlmAttemptMs.put(session.getId(), now);

      params =
          llmSessionParamService.generateSessionParams(
              session, triggerType, triggerSignal, currentQueueSize);
    } else {
      log.debug(
          "LLM cooldown active for session {}, {}s remaining",
          session.getId(),
          (llmCooldownMs - (now - lastAttempt)) / 1000);
    }

    if (params.isPresent()) {
      var p = params.get();
      lastSessionParams.put(session.getId(), p);
      log.info(
          "LLM session params for session {}: energy={}, valence={}, exploration={}, arc={},"
              + " preferGenres={}, targetQueueSize={}, insertNextCount={}",
          session.getId(),
          p.targetEnergy(),
          p.targetValence(),
          p.explorationWeight(),
          p.arc(),
          p.preferGenres(),
          p.targetQueueSize(),
          p.insertNextCount());
      persistSessionSummary(session.getId(), p.sessionSummaryUpdate());
      fillQueueWithRetry(
          session,
          p.targetEnergy(),
          p.targetValence(),
          p.explorationWeight(),
          Set.copyOf(p.avoidArtists()),
          p.preferGenres(),
          p.avoidGenres(),
          p.targetQueueSize(),
          p.insertNextCount());
    } else {
      DjSessionParams cached = lastSessionParams.getIfPresent(session.getId());
      if (cached != null) {
        log.info("Reusing cached LLM params for session {}", session.getId());
        fillQueueWithRetry(
            session,
            cached.targetEnergy(),
            cached.targetValence(),
            cached.explorationWeight(),
            Set.copyOf(cached.avoidArtists()),
            cached.preferGenres(),
            cached.avoidGenres(),
            cached.targetQueueSize(),
            cached.insertNextCount());
      } else {
        float exploration = resolveExplorationWeight(session.getUser().getId());
        log.info(
            "LLM unavailable and no cached params, using djStyle-derived exploration={} for"
                + " session {}",
            exploration,
            session.getId());
        fillQueueWithRetry(
            session,
            0.5f,
            0.5f,
            exploration,
            Set.of(),
            session.getSeedGenreList(),
            List.of(),
            40,
            0);
      }
    }
  }

  private List<AdaptiveQueueEntity> fillQueueWithRetry(
      ListeningSessionEntity session,
      float targetEnergy,
      float targetValence,
      float explorationWeight,
      Set<String> avoidArtists,
      List<String> preferGenres,
      List<String> avoidGenres,
      int dynamicQueueSize,
      int insertNextCount) {
    for (int attempt = 0; attempt < 3; attempt++) {
      try {
        return fallbackQueueService.fillQueue(
            session,
            targetEnergy,
            targetValence,
            explorationWeight,
            avoidArtists,
            preferGenres,
            avoidGenres,
            dynamicQueueSize,
            insertNextCount);
      } catch (DataIntegrityViolationException e) {
        log.warn(
            "Queue fill version conflict attempt {} for session {}", attempt + 1, session.getId());
      }
    }
    log.error("Queue fill failed after 3 retries for session {}", session.getId());
    return List.of();
  }

  @SuppressWarnings("unchecked")
  private float resolveExplorationWeight(UUID userId) {
    try {
      var prefs = contractRepository.findById(userId).orElse(null);
      if (prefs != null && prefs.getDjStyle() != null && !prefs.getDjStyle().isBlank()) {
        Map<String, Object> style = objectMapper.readValue(prefs.getDjStyle(), Map.class);
        Object pctObj = style.get("discoveryPct");
        if (pctObj instanceof Number n) {
          return Math.max(0f, Math.min(1f, n.floatValue() / 100f));
        }
        String preset = (String) style.get("preset");
        if ("adventurous".equals(preset)) return 0.6f;
        if ("smooth".equals(preset)) return 0.15f;
      }
    } catch (Exception e) {
      log.debug("Could not resolve djStyle exploration weight: {}", e.getMessage());
    }
    return 0.3f;
  }

  @Transactional
  void persistSessionSummary(UUID sessionId, String summary) {
    if (summary != null && !summary.isBlank()) {
      sessionRepository.findById(sessionId).ifPresent(s -> s.setSessionSummary(summary));
    }
  }

  private void validateSessionExists(UUID sessionId) {
    if (!sessionRepository.existsById(sessionId)) {
      throw new ResourceNotFoundException(ResourceType.ListeningSession, sessionId);
    }
  }
}
