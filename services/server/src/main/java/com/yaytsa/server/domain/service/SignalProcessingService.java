package com.yaytsa.server.domain.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yaytsa.server.error.ResourceNotFoundException;
import com.yaytsa.server.error.ResourceType;
import com.yaytsa.server.infrastructure.persistence.entity.AdaptiveQueueEntity;
import com.yaytsa.server.infrastructure.persistence.entity.ItemEntity;
import com.yaytsa.server.infrastructure.persistence.entity.ListeningSessionEntity;
import com.yaytsa.server.infrastructure.persistence.entity.PlaybackSignalEntity;
import com.yaytsa.server.infrastructure.persistence.repository.AdaptiveQueueRepository;
import com.yaytsa.server.infrastructure.persistence.repository.ItemRepository;
import com.yaytsa.server.infrastructure.persistence.repository.ListeningSessionRepository;
import com.yaytsa.server.infrastructure.persistence.repository.PlaybackSignalRepository;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class SignalProcessingService {

  private static final Logger log = LoggerFactory.getLogger(SignalProcessingService.class);
  private static final int QUEUE_LOW_THRESHOLD = 3;
  private static final int SKIP_PATTERN_COUNT = 2;
  private static final int SKIP_PATTERN_WINDOW_SIGNALS = 10;

  private final PlaybackSignalRepository signalRepository;
  private final ListeningSessionRepository sessionRepository;
  private final ItemRepository itemRepository;
  private final AdaptiveQueueRepository queueRepository;
  private final AdaptiveQueueService adaptiveQueueService;
  private final ObjectMapper objectMapper;

  public SignalProcessingService(
      PlaybackSignalRepository signalRepository,
      ListeningSessionRepository sessionRepository,
      ItemRepository itemRepository,
      AdaptiveQueueRepository queueRepository,
      AdaptiveQueueService adaptiveQueueService,
      ObjectMapper objectMapper) {
    this.signalRepository = signalRepository;
    this.sessionRepository = sessionRepository;
    this.itemRepository = itemRepository;
    this.queueRepository = queueRepository;
    this.adaptiveQueueService = adaptiveQueueService;
    this.objectMapper = objectMapper;
  }

  public record SignalResult(PlaybackSignalEntity signal, boolean triggerFired) {}

  public SignalResult processSignal(
      UUID sessionId,
      String signalType,
      UUID trackId,
      UUID queueEntryId,
      Map<String, Object> context) {

    ListeningSessionEntity session =
        sessionRepository
            .findById(sessionId)
            .orElseThrow(
                () -> new ResourceNotFoundException(ResourceType.ListeningSession, sessionId));

    ItemEntity item =
        itemRepository
            .findById(trackId)
            .orElseThrow(() -> new ResourceNotFoundException(ResourceType.Item, trackId));

    AdaptiveQueueEntity queueEntry = null;
    if (queueEntryId != null) {
      queueEntry = queueRepository.findById(queueEntryId).orElse(null);
    }

    PlaybackSignalEntity signal = new PlaybackSignalEntity();
    signal.setSession(session);
    signal.setItem(item);
    signal.setQueueEntry(queueEntry);
    signal.setSignalType(signalType);
    signal.setContext(serializeJson(context));
    signal = signalRepository.save(signal);

    boolean triggerFired = checkTriggerConditions(sessionId);

    if (triggerFired) {
      log.info("Trigger conditions met for session {}, signal type: {}", sessionId, signalType);
      String triggerType = isQueueLow(sessionId) ? "QUEUE_LOW" : "SKIP_SIGNAL";
      adaptiveQueueService.triggerDjDecision(session, triggerType, signal);
    }

    return new SignalResult(signal, triggerFired);
  }

  private boolean checkTriggerConditions(UUID sessionId) {
    boolean queueLow = isQueueLow(sessionId);
    boolean skipPattern = hasSkipPattern(sessionId);

    if (queueLow) {
      log.info(
          "Queue low trigger: session {} has {} or fewer remaining items",
          sessionId,
          QUEUE_LOW_THRESHOLD);
    }
    if (skipPattern) {
      log.info(
          "Skip pattern trigger: session {} has {}+ skips in recent signals",
          sessionId,
          SKIP_PATTERN_COUNT);
    }

    return queueLow || skipPattern;
  }

  private boolean isQueueLow(UUID sessionId) {
    List<AdaptiveQueueEntity> remaining =
        queueRepository.findBySessionIdAndStatusInOrderByPositionAsc(sessionId, List.of("QUEUED"));
    return remaining.size() <= QUEUE_LOW_THRESHOLD;
  }

  private boolean hasSkipPattern(UUID sessionId) {
    List<PlaybackSignalEntity> recentSignals =
        signalRepository.findBySessionIdOrderByCreatedAtDesc(
            sessionId, PageRequest.of(0, SKIP_PATTERN_WINDOW_SIGNALS));

    long skipCount =
        recentSignals.stream()
            .filter(
                s ->
                    "SKIP_EARLY".equals(s.getSignalType())
                        || "SKIP_IMMEDIATE".equals(s.getSignalType()))
            .count();

    return skipCount >= SKIP_PATTERN_COUNT;
  }

  private String serializeJson(Object value) {
    if (value == null) {
      return "{}";
    }
    try {
      return objectMapper.writeValueAsString(value);
    } catch (JsonProcessingException e) {
      throw new IllegalArgumentException("Invalid JSON context", e);
    }
  }
}
