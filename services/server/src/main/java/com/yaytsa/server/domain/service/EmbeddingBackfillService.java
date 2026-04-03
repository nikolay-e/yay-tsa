package com.yaytsa.server.domain.service;

import com.yaytsa.server.infrastructure.client.EmbeddingExtractionClient;
import com.yaytsa.server.infrastructure.persistence.repository.ItemRepository;
import com.yaytsa.server.infrastructure.persistence.repository.TrackFeaturesRepository;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

@Service
public class EmbeddingBackfillService {

  private static final Logger log = LoggerFactory.getLogger(EmbeddingBackfillService.class);
  private static final int BATCH_SIZE = 50;

  private final TrackFeaturesRepository featuresRepository;
  private final ItemRepository itemRepository;
  private final FeatureExtractionService featureExtractionService;
  private final EmbeddingExtractionClient embeddingClient;
  private final RadioAnchorResolver radioAnchorResolver;
  private final AtomicBoolean running = new AtomicBoolean(false);
  private final AtomicInteger processedCount = new AtomicInteger(0);

  public EmbeddingBackfillService(
      TrackFeaturesRepository featuresRepository,
      ItemRepository itemRepository,
      FeatureExtractionService featureExtractionService,
      EmbeddingExtractionClient embeddingClient,
      RadioAnchorResolver radioAnchorResolver) {
    this.featuresRepository = featuresRepository;
    this.itemRepository = itemRepository;
    this.featureExtractionService = featureExtractionService;
    this.embeddingClient = embeddingClient;
    this.radioAnchorResolver = radioAnchorResolver;
  }

  @EventListener(ApplicationReadyEvent.class)
  public void onApplicationReady() {
    Thread.startVirtualThread(
        () -> {
          try {
            Thread.sleep(5000);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
          }

          if (!embeddingClient.isAvailable()) {
            log.info("Embedding extractor not available, skipping startup backfill");
            return;
          }

          long missing = getRemainingCount();
          if (missing == 0) {
            log.info("All tracks have embeddings, no backfill needed");
            return;
          }

          log.info("Found {} tracks without embeddings, starting automatic backfill", missing);
          startBackfill();
        });
  }

  public boolean isRunning() {
    return running.get();
  }

  public int getProcessedCount() {
    return processedCount.get();
  }

  public long getRemainingCount() {
    return featuresRepository.countTracksWithoutEmbeddings();
  }

  public void startBackfill() {
    if (!running.compareAndSet(false, true)) {
      log.info("Embedding backfill already running");
      return;
    }

    processedCount.set(0);
    Thread.startVirtualThread(this::runBackfill);
  }

  private void runBackfill() {
    try {
      if (!embeddingClient.isAvailable()) {
        log.error("Embedding extractor not available, aborting backfill");
        return;
      }
      log.info("Starting embedding backfill");
      while (running.get()) {
        List<UUID> batch = featuresRepository.findTrackIdsWithoutEmbeddings(BATCH_SIZE);
        if (batch.isEmpty()) {
          log.info("Embedding backfill complete, processed {} tracks", processedCount.get());
          break;
        }

        int batchBefore = processedCount.get();
        for (UUID trackId : batch) {
          if (!running.get()) break;
          try {
            var item = itemRepository.findById(trackId).orElse(null);
            if (item == null || item.getPath() == null) continue;
            featureExtractionService.extractEmbeddingsForTrack(trackId, item.getPath());
            radioAnchorResolver.invalidateEmbeddingCache(trackId);
            processedCount.incrementAndGet();
          } catch (Exception e) {
            log.warn("Backfill failed for track {}: {}", trackId, e.getMessage());
          }
        }

        if (processedCount.get() == batchBefore) {
          log.error("Backfill made no progress on batch of {} tracks, aborting", batch.size());
          break;
        }

        int count = processedCount.get();
        if (count % 100 < BATCH_SIZE) {
          log.info("Embedding backfill progress: {} tracks processed", count);
        }
      }
    } finally {
      running.set(false);
    }
  }

  public void stopBackfill() {
    running.set(false);
  }
}
