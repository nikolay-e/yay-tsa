package com.yaytsa.server.domain.service;

import com.yaytsa.server.infrastructure.client.AudioSeparatorClient;
import com.yaytsa.server.infrastructure.persistence.entity.AudioTrackEntity;
import com.yaytsa.server.infrastructure.persistence.entity.ItemEntity;
import com.yaytsa.server.infrastructure.persistence.repository.AudioTrackRepository;
import com.yaytsa.server.infrastructure.persistence.repository.ItemRepository;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class KaraokeService {

  private static final Logger log = LoggerFactory.getLogger(KaraokeService.class);
  private static final long JOB_TTL_MS = 3600_000; // 1 hour TTL for completed jobs
  private static final long SEPARATION_TIMEOUT_MINUTES = 10;
  private static final long STALE_PROCESSING_TIMEOUT_MS =
      SEPARATION_TIMEOUT_MINUTES * 60 * 1000 + 60_000;

  private final ItemRepository itemRepository;
  private final AudioTrackRepository audioTrackRepository;
  private final AudioSeparatorClient separatorClient;
  private final Path mediaRootPath;
  private final Path stemsRootPath;
  private volatile Path realMediaRoot;
  private volatile Path realStemsRoot;

  private final Map<UUID, JobEntry> processingJobs = new ConcurrentHashMap<>();
  private final ExecutorService separationExecutor = Executors.newFixedThreadPool(2);
  private final ScheduledExecutorService cleanupScheduler =
      Executors.newSingleThreadScheduledExecutor();

  private record JobEntry(ProcessingStatus status, Instant createdAt, Instant updatedAt) {
    JobEntry withStatus(ProcessingStatus newStatus) {
      return new JobEntry(newStatus, this.createdAt, Instant.now());
    }
  }

  public enum ProcessingState {
    NOT_STARTED,
    PROCESSING,
    READY,
    FAILED
  }

  public record ProcessingStatus(ProcessingState state, String message) {
    public static ProcessingStatus notStarted() {
      return new ProcessingStatus(ProcessingState.NOT_STARTED, null);
    }

    public static ProcessingStatus processing(String message) {
      return new ProcessingStatus(ProcessingState.PROCESSING, message);
    }

    public static ProcessingStatus ready() {
      return new ProcessingStatus(ProcessingState.READY, null);
    }

    public static ProcessingStatus failed(String message) {
      return new ProcessingStatus(ProcessingState.FAILED, message);
    }
  }

  public KaraokeService(
      ItemRepository itemRepository,
      AudioTrackRepository audioTrackRepository,
      AudioSeparatorClient separatorClient,
      @Value("${yaytsa.media.library.roots:/media}") String mediaRoot,
      @Value("${yaytsa.media.karaoke.stems-path:/app/stems}") String stemsPath) {
    this.itemRepository = itemRepository;
    this.audioTrackRepository = audioTrackRepository;
    this.separatorClient = separatorClient;
    this.mediaRootPath = Paths.get(mediaRoot).toAbsolutePath().normalize();
    this.stemsRootPath = Paths.get(stemsPath).toAbsolutePath().normalize();
    initRealPaths();

    cleanupScheduler.scheduleAtFixedRate(this::cleanupExpiredJobs, 5, 5, TimeUnit.MINUTES);
  }

  private void initRealPaths() {
    try {
      this.realMediaRoot = mediaRootPath.toRealPath();
      log.info("Initialized media root path: {}", realMediaRoot);
    } catch (IOException e) {
      log.warn("Media root path not accessible at startup: {}", mediaRootPath);
      this.realMediaRoot = null;
    }
    try {
      this.realStemsRoot = stemsRootPath.toRealPath();
      log.info("Initialized stems root path: {}", realStemsRoot);
    } catch (IOException e) {
      log.warn("Stems root path not accessible at startup: {}", stemsRootPath);
      this.realStemsRoot = null;
    }
  }

  private synchronized Path getRealMediaRoot() {
    if (realMediaRoot != null) {
      return realMediaRoot;
    }
    try {
      realMediaRoot = mediaRootPath.toRealPath();
      return realMediaRoot;
    } catch (IOException e) {
      log.warn("Failed to resolve media root path: {}", e.getMessage());
      return null;
    }
  }

  private synchronized Path getRealStemsRoot() {
    if (realStemsRoot != null) {
      return realStemsRoot;
    }
    try {
      realStemsRoot = stemsRootPath.toRealPath();
      return realStemsRoot;
    } catch (IOException e) {
      log.warn("Failed to resolve stems root path: {}", e.getMessage());
      return null;
    }
  }

  private void cleanupExpiredJobs() {
    long now = Instant.now().toEpochMilli();
    processingJobs.forEach(
        (trackId, job) -> {
          ProcessingState state = job.status().state();
          long age = now - job.createdAt().toEpochMilli();

          String reason = null;
          if ((state == ProcessingState.READY || state == ProcessingState.FAILED)
              && age > JOB_TTL_MS) {
            reason = "expired " + state;
          } else if (state == ProcessingState.PROCESSING && age > STALE_PROCESSING_TIMEOUT_MS) {
            reason = "stale PROCESSING";
          }

          if (reason != null && processingJobs.remove(trackId, job)) {
            log.debug("Cleaned up {} job: {}", reason, trackId);
          }
        });
  }

  @PreDestroy
  public void shutdown() {
    log.info("Shutting down KaraokeService executors");
    cleanupScheduler.shutdown();
    separationExecutor.shutdown();
    try {
      if (!separationExecutor.awaitTermination(30, TimeUnit.SECONDS)) {
        separationExecutor.shutdownNow();
      }
      if (!cleanupScheduler.awaitTermination(5, TimeUnit.SECONDS)) {
        cleanupScheduler.shutdownNow();
      }
    } catch (InterruptedException e) {
      separationExecutor.shutdownNow();
      cleanupScheduler.shutdownNow();
      Thread.currentThread().interrupt();
    }
  }

  public ProcessingStatus getStatus(UUID trackId) {
    AudioTrackEntity track = audioTrackRepository.findById(trackId).orElse(null);

    if (track != null && Boolean.TRUE.equals(track.getKaraokeReady())) {
      if (validateStemFilesExist(track)) {
        return ProcessingStatus.ready();
      }
      resetKaraokeState(track, "Stem files missing, resetting karaoke state");
    }

    JobEntry job = processingJobs.get(trackId);
    if (job != null) {
      if (job.status().state() == ProcessingState.FAILED) {
        return ProcessingStatus.notStarted();
      }
      return job.status();
    }

    if (track != null && tryRecoverFromOrphanedFiles(track)) {
      return ProcessingStatus.ready();
    }

    return ProcessingStatus.notStarted();
  }

  private boolean tryRecoverFromOrphanedFiles(AudioTrackEntity track) {
    ItemEntity item = itemRepository.findById(track.getItemId()).orElse(null);
    if (item == null || item.getPath() == null) {
      return false;
    }

    Path audioFilePath = Paths.get(item.getPath());
    if (!isMediaPathSafe(audioFilePath)) {
      return false;
    }

    Path karaokeDir = audioFilePath.getParent().resolve(".karaoke");
    String baseName = getFileNameWithoutExtension(audioFilePath.getFileName().toString());
    Path instrumentalPath = karaokeDir.resolve(baseName + "_instrumental.wav");
    Path vocalPath = karaokeDir.resolve(baseName + "_vocals.wav");

    if (Files.exists(instrumentalPath)) {
      log.info(
          "Recovering orphaned karaoke files for track {}: {}",
          track.getItemId(),
          instrumentalPath);
      track.setKaraokeReady(true);
      track.setInstrumentalPath(instrumentalPath.toString());
      track.setVocalPath(Files.exists(vocalPath) ? vocalPath.toString() : "");
      audioTrackRepository.save(track);
      return true;
    }

    return false;
  }

  private String getFileNameWithoutExtension(String fileName) {
    int lastDot = fileName.lastIndexOf('.');
    return lastDot > 0 ? fileName.substring(0, lastDot) : fileName;
  }

  private boolean validateStemFilesExist(AudioTrackEntity track) {
    if (track.getInstrumentalPath() == null || track.getVocalPath() == null) {
      return false;
    }
    Path instrumentalPath = Paths.get(track.getInstrumentalPath()).toAbsolutePath().normalize();
    Path vocalPath = Paths.get(track.getVocalPath()).toAbsolutePath().normalize();
    return isStemPathSafe(instrumentalPath)
        && Files.exists(instrumentalPath)
        && isStemPathSafe(vocalPath)
        && Files.exists(vocalPath);
  }

  private void resetKaraokeState(AudioTrackEntity track, String reason) {
    log.warn("{} for track {}", reason, track.getItemId());
    track.setKaraokeReady(false);
    track.setInstrumentalPath(null);
    track.setVocalPath(null);
    audioTrackRepository.save(track);
    processingJobs.remove(track.getItemId());
  }

  @Async
  public void processTrack(UUID trackId) {
    JobEntry existingJob = processingJobs.get(trackId);
    if (existingJob != null) {
      ProcessingState state = existingJob.status().state();
      long ageMs = Instant.now().toEpochMilli() - existingJob.createdAt().toEpochMilli();

      if (state == ProcessingState.PROCESSING && ageMs < STALE_PROCESSING_TIMEOUT_MS) {
        log.info("Track {} is already being processed", trackId);
        return;
      }

      if (state == ProcessingState.FAILED
          || (state == ProcessingState.PROCESSING && ageMs >= STALE_PROCESSING_TIMEOUT_MS)) {
        log.info("Retrying track {} (previous state: {}, age: {}ms)", trackId, state, ageMs);
        processingJobs.remove(trackId);
      }
    }

    AudioTrackEntity track = audioTrackRepository.findById(trackId).orElse(null);
    if (track == null) {
      log.error("Track not found: {}", trackId);
      return;
    }

    if (Boolean.TRUE.equals(track.getKaraokeReady())) {
      log.info("Track {} already has karaoke stems", trackId);
      return;
    }

    ItemEntity item = itemRepository.findById(trackId).orElse(null);
    if (item == null || item.getPath() == null) {
      log.error("Track {} has no associated file path", trackId);
      updateJobStatus(trackId, ProcessingStatus.failed("Track file not found"));
      return;
    }

    Path audioFilePath = Paths.get(item.getPath());
    if (!isMediaPathSafe(audioFilePath)) {
      log.error("Path traversal attempt detected for track {}: {}", trackId, audioFilePath);
      updateJobStatus(trackId, ProcessingStatus.failed("Invalid file path"));
      return;
    }
    if (!Files.exists(audioFilePath)) {
      log.error("Audio file not found: {}", audioFilePath);
      updateJobStatus(trackId, ProcessingStatus.failed("Audio file not found"));
      return;
    }

    if (tryRecoverFromOrphanedFiles(track)) {
      log.info("Recovered existing karaoke files for track {}, skipping processing", trackId);
      updateJobStatus(trackId, ProcessingStatus.ready());
      return;
    }

    updateJobStatus(trackId, ProcessingStatus.processing("Processing..."));

    String trackIdStr = trackId.toString();

    try {
      AudioSeparatorClient.SeparationResult result =
          CompletableFuture.supplyAsync(
                  () -> separatorClient.separate(audioFilePath, trackIdStr), separationExecutor)
              .get(SEPARATION_TIMEOUT_MINUTES, TimeUnit.MINUTES);

      track.setKaraokeReady(true);
      track.setInstrumentalPath(result.instrumentalPath());
      track.setVocalPath(result.vocalPath());
      audioTrackRepository.save(track);

      updateJobStatus(trackId, ProcessingStatus.ready());
      log.info(
          "Karaoke processing completed for track {} in {}ms", trackId, result.processingTimeMs());

    } catch (TimeoutException e) {
      log.error("Karaoke processing timed out for track {}", trackId);
      updateJobStatus(trackId, ProcessingStatus.failed("Processing timed out"));
    } catch (Exception e) {
      log.error("Karaoke processing failed for track {}: {}", trackId, e.getMessage(), e);
      updateJobStatus(trackId, ProcessingStatus.failed(e.getMessage()));
    }
  }

  private void updateJobStatus(UUID trackId, ProcessingStatus status) {
    processingJobs.compute(
        trackId,
        (key, existing) -> {
          Instant now = Instant.now();
          if (existing == null) {
            return new JobEntry(status, now, now);
          }
          return existing.withStatus(status);
        });
  }

  private boolean isStemPathSafe(Path path) {
    try {
      Path mediaRoot = getRealMediaRoot();
      Path stemsRoot = getRealStemsRoot();
      if (mediaRoot == null && stemsRoot == null) {
        return false;
      }

      Path absolutePath = path.toAbsolutePath().normalize();

      if (Files.exists(absolutePath)) {
        Path realPath = absolutePath.toRealPath();
        return (mediaRoot != null && realPath.startsWith(mediaRoot))
            || (stemsRoot != null && realPath.startsWith(stemsRoot));
      }

      Path parent = absolutePath.getParent();
      if (parent == null || !Files.exists(parent)) {
        log.debug("Parent directory does not exist for stem path: {}", path);
        return false;
      }
      Path realParent = parent.toRealPath();
      return (mediaRoot != null && realParent.startsWith(mediaRoot))
          || (stemsRoot != null && realParent.startsWith(stemsRoot));

    } catch (IOException e) {
      log.warn("Stem path validation failed for {}: {}", path, e.getMessage());
      return false;
    }
  }

  private boolean isMediaPathSafe(Path path) {
    try {
      Path root = getRealMediaRoot();
      if (root == null) {
        return false;
      }

      Path absolutePath = path.toAbsolutePath().normalize();

      if (Files.exists(absolutePath)) {
        Path realPath = absolutePath.toRealPath();
        return realPath.startsWith(root);
      }

      Path parent = absolutePath.getParent();
      if (parent == null || !Files.exists(parent)) {
        log.debug("Parent directory does not exist for media path: {}", path);
        return false;
      }
      Path realParent = parent.toRealPath();
      return realParent.startsWith(root);

    } catch (IOException e) {
      log.warn("Media path validation failed for {}: {}", path, e.getMessage());
      return false;
    }
  }

  public Path getInstrumentalPath(UUID trackId) {
    AudioTrackEntity track =
        audioTrackRepository
            .findById(trackId)
            .orElseThrow(
                () ->
                    new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Track not found: " + trackId));

    if (!Boolean.TRUE.equals(track.getKaraokeReady()) || track.getInstrumentalPath() == null) {
      throw new ResponseStatusException(
          HttpStatus.NOT_FOUND, "Instrumental not available for track: " + trackId);
    }

    Path instrumentalPath = Paths.get(track.getInstrumentalPath()).toAbsolutePath().normalize();
    if (!isStemPathSafe(instrumentalPath)) {
      log.error(
          "Path traversal attempt in instrumental path for track {}: {}",
          trackId,
          instrumentalPath);
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Access denied");
    }
    if (!Files.exists(instrumentalPath)) {
      resetKaraokeState(track, "Instrumental file not found");
      throw new ResponseStatusException(
          HttpStatus.NOT_FOUND, "Instrumental file not found: " + trackId);
    }

    return instrumentalPath;
  }

  public Path getVocalPath(UUID trackId) {
    AudioTrackEntity track =
        audioTrackRepository
            .findById(trackId)
            .orElseThrow(
                () ->
                    new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Track not found: " + trackId));

    if (!Boolean.TRUE.equals(track.getKaraokeReady()) || track.getVocalPath() == null) {
      throw new ResponseStatusException(
          HttpStatus.NOT_FOUND, "Vocal track not available for track: " + trackId);
    }

    Path vocalPath = Paths.get(track.getVocalPath()).toAbsolutePath().normalize();
    if (!isStemPathSafe(vocalPath)) {
      log.error("Path traversal attempt in vocal path for track {}: {}", trackId, vocalPath);
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Access denied");
    }
    if (!Files.exists(vocalPath)) {
      resetKaraokeState(track, "Vocal file not found");
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Vocal file not found: " + trackId);
    }

    return vocalPath;
  }

  public void clearProcessingStatus(UUID trackId) {
    processingJobs.remove(trackId);
  }
}
