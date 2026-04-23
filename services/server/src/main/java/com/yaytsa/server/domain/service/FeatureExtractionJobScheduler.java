package com.yaytsa.server.domain.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class FeatureExtractionJobScheduler {

  private final FeatureExtractionService featureExtractionService;
  private final TasteProfileService tasteProfileService;

  @Value("${yaytsa.media.feature-extraction.batch-size:10}")
  private int batchSize;

  public FeatureExtractionJobScheduler(
      FeatureExtractionService featureExtractionService, TasteProfileService tasteProfileService) {
    this.featureExtractionService = featureExtractionService;
    this.tasteProfileService = tasteProfileService;
  }

  @Scheduled(fixedDelayString = "${yaytsa.media.feature-extraction.poll-interval-ms:30000}")
  public void processPendingJobs() {
    featureExtractionService.resetStaleJobs();

    long pending = featureExtractionService.getPendingCount();
    if (pending == 0) {
      return;
    }
    long processing = featureExtractionService.getProcessingCount();
    if (processing > 0) {
      log.debug("{} jobs still processing, {} pending — skipping this cycle", processing, pending);
      return;
    }
    log.debug("Processing pending feature extraction jobs, {} in queue", pending);
    featureExtractionService.processPendingJobs(batchSize);
  }

  @Scheduled(cron = "${yaytsa.adaptive-dj.taste-profile.rebuild-cron:0 0 4 * * *}")
  public void rebuildTasteProfiles() {
    log.info("Starting scheduled taste profile rebuild");
    tasteProfileService.rebuildAllProfiles();
  }
}
