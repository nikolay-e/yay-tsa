package com.yaytsa.server.domain.service;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class SignalProcessingService {

  private static final Logger log = LoggerFactory.getLogger(SignalProcessingService.class);

  private final SignalPersistenceService persistenceService;
  private final AffinityAggregationService affinityAggregationService;
  private final Executor signalAsyncExecutor;
  private final int queueLowThreshold;

  public SignalProcessingService(
      SignalPersistenceService persistenceService,
      AffinityAggregationService affinityAggregationService,
      @Qualifier("signalAsyncExecutor") Executor signalAsyncExecutor,
      @Value("${yaytsa.adaptive-dj.queue.trigger-threshold:8}") int queueLowThreshold) {
    this.persistenceService = persistenceService;
    this.affinityAggregationService = affinityAggregationService;
    this.signalAsyncExecutor = signalAsyncExecutor;
    this.queueLowThreshold = queueLowThreshold;
  }

  public record SignalResult(
      SignalPersistenceService.PersistResult persist,
      boolean triggerFired,
      UUID sessionId,
      String triggerType) {}

  public SignalResult processSignal(
      UUID sessionId,
      String signalType,
      UUID trackId,
      UUID queueEntryId,
      Map<String, Object> context) {
    var persisted =
        persistenceService.persistSignal(sessionId, signalType, trackId, queueEntryId, context);

    CompletableFuture.runAsync(
        () -> {
          try {
            affinityAggregationService.updateAffinityFromSignal(
                persisted.userId(), trackId, signalType);
          } catch (Exception e) {
            log.warn(
                "Affinity update failed for user {} track {}: {}",
                persisted.userId(),
                trackId,
                e.getMessage());
          }
        },
        signalAsyncExecutor);

    boolean queueLow = persistenceService.isQueueLow(sessionId, queueLowThreshold);
    boolean skipPattern = persistenceService.hasSkipPattern(sessionId);
    if (queueLow)
      log.info(
          "Queue low trigger: session {} has {} or fewer remaining items",
          sessionId,
          queueLowThreshold);
    if (skipPattern)
      log.info("Skip pattern trigger: session {} has 2+ skips in recent signals", sessionId);
    String triggerType = queueLow ? "QUEUE_LOW" : skipPattern ? "SKIP_SIGNAL" : null;
    return new SignalResult(persisted, queueLow || skipPattern, sessionId, triggerType);
  }
}
