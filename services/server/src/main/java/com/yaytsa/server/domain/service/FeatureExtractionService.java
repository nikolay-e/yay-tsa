package com.yaytsa.server.domain.service;

import com.yaytsa.server.infrastructure.client.FeatureExtractionClient;
import com.yaytsa.server.infrastructure.client.FeatureExtractionClient.ExtractionResult;
import com.yaytsa.server.infrastructure.persistence.entity.FeatureExtractionJobEntity;
import com.yaytsa.server.infrastructure.persistence.entity.ItemEntity;
import com.yaytsa.server.infrastructure.persistence.entity.TrackFeaturesEntity;
import com.yaytsa.server.infrastructure.persistence.repository.FeatureExtractionJobRepository;
import com.yaytsa.server.infrastructure.persistence.repository.ItemRepository;
import com.yaytsa.server.infrastructure.persistence.repository.TrackFeaturesRepository;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.Semaphore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class FeatureExtractionService {

  private static final Logger log = LoggerFactory.getLogger(FeatureExtractionService.class);
  private static final int MAX_ATTEMPTS = 3;

  private final FeatureExtractionClient extractionClient;
  private final FeatureExtractionJobRepository jobRepository;
  private final TrackFeaturesRepository featuresRepository;
  private final ItemRepository itemRepository;
  private final Executor taskExecutor;
  private final Semaphore extractionSemaphore;
  private final TransactionTemplate transactionTemplate;

  public FeatureExtractionService(
      FeatureExtractionClient extractionClient,
      FeatureExtractionJobRepository jobRepository,
      TrackFeaturesRepository featuresRepository,
      ItemRepository itemRepository,
      @Qualifier("applicationTaskExecutor") Executor taskExecutor,
      TransactionTemplate transactionTemplate,
      @Value("${yaytsa.media.feature-extraction.max-concurrent:2}") int maxConcurrent) {
    this.extractionClient = extractionClient;
    this.jobRepository = jobRepository;
    this.featuresRepository = featuresRepository;
    this.itemRepository = itemRepository;
    this.taskExecutor = taskExecutor;
    this.transactionTemplate = transactionTemplate;
    this.extractionSemaphore = new Semaphore(maxConcurrent);
  }

  public void enqueueExtraction(UUID trackId) {
    if (featuresRepository.findByTrackId(trackId).isPresent()) {
      return;
    }

    if (jobRepository.findByItemId(trackId).isPresent()) {
      return;
    }

    var item = itemRepository.findById(trackId).orElse(null);
    if (item == null) {
      return;
    }

    var job = new FeatureExtractionJobEntity();
    job.setItem(item);
    job.setStatus("PENDING");
    jobRepository.save(job);

    log.debug("Enqueued feature extraction for track {}", trackId);
  }

  public void enqueueAllUnextracted() {
    var tracksWithoutFeatures =
        itemRepository.findAudioTracksWithoutFeatures(PageRequest.of(0, 10000));

    int enqueued = 0;
    for (var item : tracksWithoutFeatures) {
      if (jobRepository.findByItemId(item.getId()).isEmpty()) {
        var job = new FeatureExtractionJobEntity();
        job.setItem(item);
        job.setStatus("PENDING");
        jobRepository.save(job);
        enqueued++;
      }
    }

    if (enqueued > 0) {
      log.info("Enqueued {} tracks for feature extraction", enqueued);
    }
  }

  public void processPendingJobs(int batchSize) {
    if (!extractionClient.isAvailable()) {
      log.debug("Feature extractor not available, skipping job processing");
      return;
    }

    var pendingJobs =
        jobRepository.findByStatusOrderByCreatedAtAsc("PENDING", PageRequest.of(0, batchSize));

    if (pendingJobs.isEmpty()) {
      return;
    }

    log.info("Processing {} pending feature extraction jobs", pendingJobs.size());

    for (var job : pendingJobs) {
      taskExecutor.execute(() -> processJob(job));
    }
  }

  private void processJob(FeatureExtractionJobEntity job) {
    try {
      extractionSemaphore.acquire();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return;
    }

    try {
      job.setStatus("PROCESSING");
      job.setAttempts(job.getAttempts() + 1);
      jobRepository.save(job);

      ItemEntity item = job.getItem();
      String filePath = item.getPath();

      ExtractionResult result = extractionClient.extract(item.getId(), filePath);
      transactionTemplate.executeWithoutResult(status -> saveFeatures(item, result));

      job.setStatus("DONE");
      jobRepository.save(job);

      log.info(
          "Feature extraction completed for track {} in {}ms",
          item.getId(),
          result.processingTimeMs());

    } catch (Exception e) {
      log.error("Feature extraction failed for job {}: {}", job.getId(), e.getMessage());
      job.setErrorMessage(e.getMessage());
      if (job.getAttempts() >= MAX_ATTEMPTS) {
        job.setStatus("FAILED");
      } else {
        job.setStatus("PENDING");
      }
      jobRepository.save(job);
    } finally {
      extractionSemaphore.release();
    }
  }

  private void saveFeatures(ItemEntity item, ExtractionResult result) {
    var entity = featuresRepository.findByTrackId(item.getId()).orElseGet(TrackFeaturesEntity::new);

    entity.setItem(item);

    Map<String, Object> f = result.features();
    entity.setBpm(floatVal(f, "bpm"));
    entity.setBpmConfidence(floatVal(f, "bpm_confidence"));
    entity.setMusicalKey(stringVal(f, "key"));
    entity.setKeyConfidence(floatVal(f, "key_confidence"));
    entity.setEnergy(floatVal(f, "energy"));
    entity.setLoudnessIntegrated(floatVal(f, "loudness_integrated"));
    entity.setLoudnessRange(floatVal(f, "loudness_range"));
    entity.setAverageLoudness(floatVal(f, "average_loudness"));
    entity.setValence(floatVal(f, "valence"));
    entity.setArousal(floatVal(f, "arousal"));
    entity.setDanceability(floatVal(f, "danceability"));
    entity.setVocalInstrumental(floatVal(f, "vocal_instrumental"));
    entity.setSpectralComplexity(floatVal(f, "spectral_complexity"));
    entity.setDissonance(floatVal(f, "dissonance"));
    entity.setOnsetRate(floatVal(f, "onset_rate"));

    if (result.embeddingDiscogs() != null) {
      entity.setEmbeddingDiscogs(toFloatArray(result.embeddingDiscogs()));
    }
    if (result.embeddingMusicnn() != null) {
      entity.setEmbeddingMusicnn(toFloatArray(result.embeddingMusicnn()));
    }

    entity.setExtractedAt(OffsetDateTime.now());
    entity.setExtractorVersion("1.0");

    featuresRepository.save(entity);
  }

  public long getPendingCount() {
    return jobRepository.countByStatus("PENDING");
  }

  public long getProcessingCount() {
    return jobRepository.countByStatus("PROCESSING");
  }

  private static Float floatVal(Map<String, Object> map, String key) {
    Object val = map.get(key);
    if (val instanceof Number n) {
      return n.floatValue();
    }
    return null;
  }

  private static String stringVal(Map<String, Object> map, String key) {
    Object val = map.get(key);
    return val != null ? val.toString() : null;
  }

  private static float[] toFloatArray(List<Float> list) {
    float[] arr = new float[list.size()];
    for (int i = 0; i < list.size(); i++) {
      Float v = list.get(i);
      arr[i] = v != null ? v : 0f;
    }
    return arr;
  }
}
