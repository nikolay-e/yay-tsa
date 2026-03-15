package com.yaytsa.server.domain.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yaytsa.server.error.ResourceNotFoundException;
import com.yaytsa.server.error.ResourceType;
import com.yaytsa.server.infrastructure.persistence.entity.PlaybackSignalEntity;
import com.yaytsa.server.infrastructure.persistence.repository.AdaptiveQueueRepository;
import com.yaytsa.server.infrastructure.persistence.repository.ItemRepository;
import com.yaytsa.server.infrastructure.persistence.repository.ListeningSessionRepository;
import com.yaytsa.server.infrastructure.persistence.repository.PlaybackSignalRepository;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class SignalProcessingService {

  private static final Logger log = LoggerFactory.getLogger(SignalProcessingService.class);
  private static final int SKIP_PATTERN_COUNT = 2;
  private static final int SKIP_PATTERN_WINDOW = 10;
  private static final Set<String> CONSUMED_SIGNALS =
      Set.of("PLAY_COMPLETE", "SKIP_EARLY", "SKIP_MID", "SKIP_LATE", "THUMBS_DOWN");

  private final PlaybackSignalRepository signalRepository;
  private final ListeningSessionRepository sessionRepository;
  private final ItemRepository itemRepository;
  private final AdaptiveQueueRepository queueRepository;
  private final AffinityAggregationService affinityAggregationService;
  private final ObjectMapper objectMapper;
  private final int queueLowThreshold;

  public SignalProcessingService(
      PlaybackSignalRepository signalRepository,
      ListeningSessionRepository sessionRepository,
      ItemRepository itemRepository,
      AdaptiveQueueRepository queueRepository,
      AffinityAggregationService affinityAggregationService,
      ObjectMapper objectMapper,
      @Value("${yaytsa.adaptive-dj.queue.trigger-threshold:8}") int queueLowThreshold) {
    this.signalRepository = signalRepository;
    this.sessionRepository = sessionRepository;
    this.itemRepository = itemRepository;
    this.queueRepository = queueRepository;
    this.affinityAggregationService = affinityAggregationService;
    this.objectMapper = objectMapper;
    this.queueLowThreshold = queueLowThreshold;
  }

  public record SignalResult(
      PlaybackSignalEntity signal, boolean triggerFired, UUID sessionId, String triggerType) {}

  public SignalResult processSignal(
      UUID sessionId,
      String signalType,
      UUID trackId,
      UUID queueEntryId,
      Map<String, Object> context) {
    var session =
        sessionRepository
            .findById(sessionId)
            .orElseThrow(
                () -> new ResourceNotFoundException(ResourceType.ListeningSession, sessionId));
    var item =
        itemRepository
            .findById(trackId)
            .orElseThrow(() -> new ResourceNotFoundException(ResourceType.Item, trackId));

    var signal = new PlaybackSignalEntity();
    signal.setSession(session);
    signal.setItem(item);
    signal.setQueueEntry(
        queueEntryId != null ? queueRepository.findById(queueEntryId).orElse(null) : null);
    signal.setSignalType(signalType);
    signal.setContext(serializeJson(context));
    signal = signalRepository.save(signal);

    UUID userId = session.getUser().getId();
    Thread.ofVirtual()
        .start(
            () -> {
              try {
                affinityAggregationService.updateAffinityFromSignal(userId, trackId, signalType);
              } catch (Exception e) {
                log.warn(
                    "Affinity update failed for user {} track {}: {}",
                    userId,
                    trackId,
                    e.getMessage());
              }
            });

    if (CONSUMED_SIGNALS.contains(signalType)) {
      markQueueEntryConsumed(sessionId, trackId, signalType);
    } else if ("PLAY_START".equals(signalType) || "QUEUE_JUMP".equals(signalType)) {
      skipPrecedingEntries(sessionId, trackId);
    }

    boolean queueLow = isQueueLow(sessionId);
    boolean skipPattern = hasSkipPattern(sessionId);
    if (queueLow)
      log.info(
          "Queue low trigger: session {} has {} or fewer remaining items",
          sessionId,
          queueLowThreshold);
    if (skipPattern)
      log.info(
          "Skip pattern trigger: session {} has {}+ skips in recent signals",
          sessionId,
          SKIP_PATTERN_COUNT);
    String triggerType = queueLow ? "QUEUE_LOW" : skipPattern ? "SKIP_SIGNAL" : null;
    return new SignalResult(signal, queueLow || skipPattern, sessionId, triggerType);
  }

  private void markQueueEntryConsumed(UUID sessionId, UUID trackId, String signalType) {
    String newStatus = "PLAY_COMPLETE".equals(signalType) ? "PLAYED" : "SKIPPED";
    OffsetDateTime now = OffsetDateTime.now();
    queueRepository.skipEntriesBeforeTrack(sessionId, trackId, now);
    queueRepository.markTrackConsumed(sessionId, trackId, newStatus, now);
  }

  private void skipPrecedingEntries(UUID sessionId, UUID trackId) {
    queueRepository.skipEntriesBeforeTrack(sessionId, trackId, OffsetDateTime.now());
  }

  private boolean isQueueLow(UUID sessionId) {
    return queueRepository.countBySessionIdAndStatusIn(sessionId, List.of("QUEUED"))
        < queueLowThreshold;
  }

  private boolean hasSkipPattern(UUID sessionId) {
    return signalRepository
            .findBySessionIdOrderByCreatedAtDesc(sessionId, PageRequest.of(0, SKIP_PATTERN_WINDOW))
            .stream()
            .filter(
                s ->
                    "SKIP_EARLY".equals(s.getSignalType())
                        || "THUMBS_DOWN".equals(s.getSignalType()))
            .count()
        >= SKIP_PATTERN_COUNT;
  }

  private String serializeJson(Object value) {
    if (value == null) return "{}";
    try {
      return objectMapper.writeValueAsString(value);
    } catch (JsonProcessingException e) {
      throw new IllegalArgumentException("Invalid JSON context", e);
    }
  }
}
