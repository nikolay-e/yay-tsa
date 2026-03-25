package com.yaytsa.server.domain.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class FeatureExtractionJobScheduler {

  private static final Logger log = LoggerFactory.getLogger(FeatureExtractionJobScheduler.class);

  private final FeatureExtractionService featureExtractionService;
  private final TasteProfileService tasteProfileService;
  private final RadioSeedService radioSeedService;

  @Value("${yaytsa.media.feature-extraction.batch-size:10}")
  private int batchSize;

  public FeatureExtractionJobScheduler(
      FeatureExtractionService featureExtractionService,
      TasteProfileService tasteProfileService,
      RadioSeedService radioSeedService) {
    this.featureExtractionService = featureExtractionService;
    this.tasteProfileService = tasteProfileService;
    this.radioSeedService = radioSeedService;
  }

  @Scheduled(fixedDelayString = "${yaytsa.media.feature-extraction.poll-interval-ms:30000}")
  public void processPendingJobs() {
    long pending = featureExtractionService.getPendingCount();
    if (pending == 0) {
      return;
    }
    log.debug("Processing pending feature extraction jobs, {} in queue", pending);
    featureExtractionService.processPendingJobs(batchSize);
  }

  @Scheduled(cron = "${yaytsa.adaptive-dj.taste-profile.rebuild-cron:0 0 4 * * *}")
  public void rebuildTasteProfiles() {
    log.info("Starting scheduled taste profile rebuild");
    tasteProfileService.rebuildAllProfiles();
    radioSeedService.invalidateCache();
    log.info("Radio seeds cache invalidated after taste profile rebuild");
  }
}
