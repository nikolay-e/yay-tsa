package com.yaytsa.server.domain.service;

import com.yaytsa.server.infrastructure.persistence.repository.AudioTrackRepository;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class KaraokeBackfillService {

  private static final long PAUSE_POLL_MS = 500;

  private final AudioTrackRepository audioTrackRepository;
  private final KaraokeService karaokeService;
  private final boolean backfillEnabled;
  private final int batchSize;
  private final long delayBetweenTracksMs;

  private final AtomicBoolean running = new AtomicBoolean(false);
  private final AtomicBoolean paused = new AtomicBoolean(false);
  private final AtomicInteger processedCount = new AtomicInteger(0);

  public KaraokeBackfillService(
      AudioTrackRepository audioTrackRepository,
      @Lazy KaraokeService karaokeService,
      @Value("${yaytsa.media.karaoke.backfill-enabled:false}") boolean backfillEnabled,
      @Value("${yaytsa.media.karaoke.backfill-batch-size:20}") int batchSize,
      @Value("${yaytsa.media.karaoke.backfill-delay-between-tracks-ms:2000}")
          long delayBetweenTracksMs) {
    this.audioTrackRepository = audioTrackRepository;
    this.karaokeService = karaokeService;
    this.backfillEnabled = backfillEnabled;
    this.batchSize = batchSize;
    this.delayBetweenTracksMs = delayBetweenTracksMs;
  }

  @EventListener(ApplicationReadyEvent.class)
  public void onApplicationReady() {
    if (!backfillEnabled) {
      return;
    }
    Thread.startVirtualThread(
        () -> {
          long delayMs = 10_000;
          for (int attempt = 1; attempt <= 10; attempt++) {
            try {
              Thread.sleep(delayMs);
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
              return;
            }

            if (karaokeService.isSeparatorHealthy()) {
              break;
            }

            if (attempt == 10) {
              log.info(
                  "Audio separator not available after {} attempts, giving up backfill", attempt);
              return;
            }
            log.info(
                "Audio separator not available (attempt {}), retrying in {}s",
                attempt,
                delayMs * 2 / 1000);
            delayMs = Math.min(delayMs * 2, 120_000);
          }

          long remaining = getRemainingCount();
          if (remaining == 0) {
            log.info("All tracks have karaoke stems, no backfill needed");
            return;
          }

          log.info("Found {} tracks without karaoke stems, starting automatic backfill", remaining);
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
    return audioTrackRepository.countUnprocessedKaraokeTracks();
  }

  public void startBackfill() {
    if (!running.compareAndSet(false, true)) {
      log.info("Karaoke backfill already running");
      return;
    }
    processedCount.set(0);
    paused.set(false);
    Thread.startVirtualThread(this::runBackfill);
  }

  public void stopBackfill() {
    running.set(false);
    paused.set(false);
  }

  public void pause() {
    paused.set(true);
  }

  public void resume() {
    paused.set(false);
  }

  private void runBackfill() {
    try {
      if (!karaokeService.isSeparatorHealthy()) {
        log.error("Audio separator not available, aborting karaoke backfill");
        return;
      }
      log.info("Starting karaoke backfill");

      while (running.get()) {
        waitWhilePaused();
        if (!running.get()) break;

        List<UUID> batch =
            audioTrackRepository.findUnprocessedKaraokeTrackIdsPrioritized(batchSize);
        if (batch.isEmpty()) {
          log.info("Karaoke backfill complete, processed {} tracks", processedCount.get());
          break;
        }

        int batchBefore = processedCount.get();
        for (UUID trackId : batch) {
          if (!running.get()) break;

          waitWhilePaused();
          if (!running.get()) break;

          try {
            karaokeService.processTrackSync(trackId);
            processedCount.incrementAndGet();
          } catch (Exception e) {
            log.warn("Karaoke backfill failed for track {}: {}", trackId, e.getMessage());
          }

          if (delayBetweenTracksMs > 0 && running.get()) {
            try {
              Thread.sleep(delayBetweenTracksMs);
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
              break;
            }
          }
        }

        if (processedCount.get() == batchBefore) {
          log.error(
              "Karaoke backfill made no progress on batch of {} tracks, aborting", batch.size());
          break;
        }

        int count = processedCount.get();
        if (count % 100 < batchSize) {
          log.info("Karaoke backfill progress: {} tracks processed", count);
        }
      }
    } finally {
      running.set(false);
      paused.set(false);
    }
  }

  private void waitWhilePaused() {
    while (paused.get() && running.get()) {
      try {
        Thread.sleep(PAUSE_POLL_MS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        running.set(false);
        break;
      }
    }
  }
}
