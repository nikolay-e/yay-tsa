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
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SignalPersistenceService {

  private static final int SKIP_PATTERN_COUNT = 2;
  private static final int SKIP_PATTERN_WINDOW = 10;

  private final PlaybackSignalRepository signalRepository;
  private final ListeningSessionRepository sessionRepository;
  private final ItemRepository itemRepository;
  private final AdaptiveQueueRepository queueRepository;
  private final ObjectMapper objectMapper;

  public SignalPersistenceService(
      PlaybackSignalRepository signalRepository,
      ListeningSessionRepository sessionRepository,
      ItemRepository itemRepository,
      AdaptiveQueueRepository queueRepository,
      ObjectMapper objectMapper) {
    this.signalRepository = signalRepository;
    this.sessionRepository = sessionRepository;
    this.itemRepository = itemRepository;
    this.queueRepository = queueRepository;
    this.objectMapper = objectMapper;
  }

  public record PersistResult(PlaybackSignalEntity signal, UUID userId) {}

  @Transactional
  public PersistResult persistSignal(
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

    updateQueueState(sessionId, trackId, signalType);

    return new PersistResult(signal, session.getUser().getId());
  }

  @Transactional(readOnly = true)
  public boolean isQueueLow(UUID sessionId, int threshold) {
    return queueRepository.countBySessionIdAndStatusIn(sessionId, List.of("QUEUED")) < threshold;
  }

  @Transactional(readOnly = true)
  public boolean hasSkipPattern(UUID sessionId) {
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

  private static final java.util.Set<String> CONSUMED_SIGNALS =
      java.util.Set.of("PLAY_COMPLETE", "SKIP_EARLY", "SKIP_MID", "SKIP_LATE", "THUMBS_DOWN");

  private void updateQueueState(UUID sessionId, UUID trackId, String signalType) {
    if (CONSUMED_SIGNALS.contains(signalType)) {
      String newStatus = "PLAY_COMPLETE".equals(signalType) ? "PLAYED" : "SKIPPED";
      OffsetDateTime now = OffsetDateTime.now();
      queueRepository.skipEntriesBeforeTrack(sessionId, trackId, now);
      queueRepository.markTrackConsumed(sessionId, trackId, newStatus, now);
    } else if ("PLAY_START".equals(signalType) || "QUEUE_JUMP".equals(signalType)) {
      queueRepository.skipEntriesBeforeTrack(sessionId, trackId, OffsetDateTime.now());
    }
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
