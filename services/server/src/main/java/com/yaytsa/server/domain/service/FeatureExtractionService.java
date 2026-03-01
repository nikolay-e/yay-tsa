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
    if (featuresRepository.findByTrackId(trackId).isPresent()) return;
    if (jobRepository.findByItemId(trackId).isPresent()) return;
    var item = itemRepository.findById(trackId).orElse(null);
    if (item == null) return;
    createJob(item);
    log.debug("Enqueued feature extraction for track {}", trackId);
  }

  public void enqueueAllUnextracted() {
    var tracks = itemRepository.findAudioTracksWithoutFeatures(PageRequest.of(0, 10000));
    int enqueued = 0;
    for (var item : tracks) {
      if (jobRepository.findByItemId(item.getId()).isEmpty()) {
        createJob(item);
        enqueued++;
      }
    }
    if (enqueued > 0) log.info("Enqueued {} tracks for feature extraction", enqueued);
  }

  public void processPendingJobs(int batchSize) {
    if (!extractionClient.isAvailable()) {
      log.debug("Feature extractor not available, skipping job processing");
      return;
    }
    var jobs = jobRepository.findByStatusWithItem("PENDING", PageRequest.of(0, batchSize));
    if (jobs.isEmpty()) return;
    log.info("Processing {} pending feature extraction jobs", jobs.size());
    for (var job : jobs) {
      UUID jobId = job.getId();
      UUID trackId = job.getItem().getId();
      String trackPath = job.getItem().getPath();
      taskExecutor.execute(() -> processJob(jobId, trackId, trackPath));
    }
  }

  public long getPendingCount() {
    return jobRepository.countByStatus("PENDING");
  }

  public long getProcessingCount() {
    return jobRepository.countByStatus("PROCESSING");
  }

  private void createJob(ItemEntity item) {
    var job = new FeatureExtractionJobEntity();
    job.setItem(item);
    job.setStatus("PENDING");
    jobRepository.save(job);
  }

  private void processJob(UUID jobId, UUID trackId, String trackPath) {
    try {
      extractionSemaphore.acquire();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return;
    }
    try {
      transactionTemplate.executeWithoutResult(
          status -> {
            var job = jobRepository.findById(jobId).orElse(null);
            if (job == null) return;
            job.setStatus("PROCESSING");
            job.setAttempts(job.getAttempts() + 1);
            jobRepository.save(job);
          });
      ExtractionResult result = extractionClient.extract(trackId, trackPath);
      transactionTemplate.executeWithoutResult(
          status -> {
            var item = itemRepository.findById(trackId).orElse(null);
            if (item == null) return;
            saveFeatures(item, result);
            var job = jobRepository.findById(jobId).orElse(null);
            if (job != null) {
              job.setStatus("DONE");
              jobRepository.save(job);
            }
          });
      log.info(
          "Feature extraction completed for track {} in {}ms", trackId, result.processingTimeMs());
    } catch (Exception e) {
      log.error("Feature extraction failed for job {}: {}", jobId, e.getMessage());
      transactionTemplate.executeWithoutResult(
          status -> {
            var job = jobRepository.findById(jobId).orElse(null);
            if (job != null) {
              job.setErrorMessage(e.getMessage());
              job.setStatus(job.getAttempts() >= MAX_ATTEMPTS ? "FAILED" : "PENDING");
              jobRepository.save(job);
            }
          });
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
    if (result.embeddingDiscogs() != null)
      entity.setEmbeddingDiscogs(toFloatArray(result.embeddingDiscogs()));
    if (result.embeddingMusicnn() != null)
      entity.setEmbeddingMusicnn(toFloatArray(result.embeddingMusicnn()));
    entity.setExtractedAt(OffsetDateTime.now());
    entity.setExtractorVersion("1.0");
    featuresRepository.save(entity);
  }

  private static Float floatVal(Map<String, Object> map, String key) {
    return map.get(key) instanceof Number n ? n.floatValue() : null;
  }

  private static String stringVal(Map<String, Object> map, String key) {
    return map.get(key) != null ? map.get(key).toString() : null;
  }

  private static float[] toFloatArray(List<?> list) {
    float[] arr = new float[list.size()];
    for (int i = 0; i < list.size(); i++)
      arr[i] = list.get(i) instanceof Number n ? n.floatValue() : 0f;
    return arr;
  }
}
