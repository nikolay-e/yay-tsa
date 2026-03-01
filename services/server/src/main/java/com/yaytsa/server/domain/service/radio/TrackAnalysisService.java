package com.yaytsa.server.domain.service.radio;

import com.yaytsa.server.infrastructure.client.EssentiaAnalysisClient;
import com.yaytsa.server.infrastructure.persistence.entity.AudioTrackEntity;
import com.yaytsa.server.infrastructure.persistence.entity.ItemEntity;
import com.yaytsa.server.infrastructure.persistence.entity.TrackAudioFeaturesEntity;
import com.yaytsa.server.infrastructure.persistence.repository.AudioTrackRepository;
import com.yaytsa.server.infrastructure.persistence.repository.TrackAudioFeaturesRepository;
import jakarta.persistence.EntityManager;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class TrackAnalysisService {

  private static final Logger log = LoggerFactory.getLogger(TrackAnalysisService.class);
  private static final int BATCH_SIZE = 10;
  private static final long BATCH_PAUSE_MS = 1000;

  private static final String[] MOODS = {
    "happy", "sad", "calm", "energetic", "aggressive", "romantic", "melancholic", "nostalgic"
  };

  private final TrackAudioFeaturesRepository featuresRepository;
  private final AudioTrackRepository audioTrackRepository;
  private final EssentiaAnalysisClient essentiaClient;
  private final TransactionTemplate transactionTemplate;
  private final EntityManager entityManager;

  private final AtomicBoolean batchRunning = new AtomicBoolean(false);

  public TrackAnalysisService(
      TrackAudioFeaturesRepository featuresRepository,
      AudioTrackRepository audioTrackRepository,
      EssentiaAnalysisClient essentiaClient,
      TransactionTemplate transactionTemplate,
      EntityManager entityManager) {
    this.featuresRepository = featuresRepository;
    this.audioTrackRepository = audioTrackRepository;
    this.essentiaClient = essentiaClient;
    this.transactionTemplate = transactionTemplate;
    this.entityManager = entityManager;
  }

  public record AnalysisStats(long total, long analyzed, long unanalyzed, boolean batchRunning) {}

  public AnalysisStats getStats() {
    long total = featuresRepository.countTotalTracks();
    long analyzed = featuresRepository.countAnalyzed();
    return new AnalysisStats(total, analyzed, total - analyzed, batchRunning.get());
  }

  public boolean analyzeTrack(UUID itemId) {
    if (featuresRepository.existsById(itemId)) {
      log.debug("Track {} already analyzed, skipping", itemId);
      return true;
    }

    var trackOpt = audioTrackRepository.findByIdWithRelations(itemId);
    if (trackOpt.isEmpty()) {
      log.warn("Track {} not found", itemId);
      return false;
    }

    AudioTrackEntity track = trackOpt.get();
    String audioPathStr = track.getItem() != null ? track.getItem().getPath() : null;
    if (audioPathStr == null) {
      log.warn("Track {} has no file path, falling back to hash-based analysis", itemId);
      return saveFeatures(itemId, hashFallback(track), "essentia-fallback", "hash-v1");
    }

    String title = track.getItem().getName();
    String artist = track.getAlbumArtist() != null ? track.getAlbumArtist().getName() : "Unknown";
    log.info("Analysing track: {} — {} via Essentia", artist, title);

    if (!essentiaClient.isHealthy()) {
      log.warn("Essentia analyzer unavailable, using hash-based fallback for track {}", itemId);
      return saveFeatures(itemId, hashFallback(track), "essentia-fallback", "hash-v1");
    }

    Optional<EssentiaAnalysisClient.AnalysisResult> result =
        essentiaClient.analyze(Path.of(audioPathStr));

    if (result.isEmpty()) {
      log.warn("Essentia returned no result for {} — {}, using hash fallback", artist, title);
      return saveFeatures(itemId, hashFallback(track), "essentia-fallback", "hash-v1");
    }

    EssentiaAnalysisClient.AnalysisResult r = result.get();
    String language = detectLanguage(track);

    FeaturesSnapshot snapshot = new FeaturesSnapshot(
        r.mood(), r.energy(), language, null, r.valence(), r.danceability());

    log.info("Analysed {} — {}: mood={}, energy={}, lang={}", artist, title,
        r.mood(), r.energy(), language);

    return saveFeatures(itemId, snapshot, "essentia", "musicnn-v1");
  }

  public void startBatchAnalysis() {
    if (!batchRunning.compareAndSet(false, true)) {
      log.info("Batch analysis already running");
      return;
    }

    Thread.startVirtualThread(
        () -> {
          try {
            log.info("Starting batch analysis");
            Set<UUID> failedIds = new HashSet<>();
            final int MAX_FAILURES = 1000;
            while (batchRunning.get()) {
              if (failedIds.size() >= MAX_FAILURES) {
                log.warn("Batch analysis stopped: too many failures ({})", failedIds.size());
                break;
              }
              List<UUID> unanalyzed = featuresRepository.findUnanalyzedTrackIdsNative(BATCH_SIZE);
              unanalyzed = unanalyzed.stream().filter(id -> !failedIds.contains(id)).toList();
              if (unanalyzed.isEmpty()) {
                log.info("Batch analysis complete: no more unanalyzed tracks");
                break;
              }
              for (UUID itemId : unanalyzed) {
                if (!batchRunning.get()) break;
                try {
                  if (!analyzeTrack(itemId)) {
                    failedIds.add(itemId);
                  }
                } catch (Exception e) {
                  log.warn("Failed to analyze track {}: {}", itemId, e.getMessage());
                  failedIds.add(itemId);
                }
                Thread.sleep(BATCH_PAUSE_MS);
              }
            }
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.info("Batch analysis interrupted");
          } finally {
            batchRunning.set(false);
            log.info("Batch analysis stopped");
          }
        });
  }

  public void stopBatchAnalysis() {
    batchRunning.set(false);
    log.info("Batch analysis stop requested");
  }

  public void analyzeTrackAsync(UUID itemId) {
    Thread.startVirtualThread(
        () -> {
          try {
            for (int attempt = 1; attempt <= 3; attempt++) {
              Thread.sleep(attempt * 1000L);
              if (audioTrackRepository.existsById(itemId)) {
                analyzeTrack(itemId);
                return;
              }
            }
            log.warn("Track {} not found after retries, skipping async analysis", itemId);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          } catch (Exception e) {
            log.warn("Async analysis failed for track {}: {}", itemId, e.getMessage());
          }
        });
  }

  // ── private helpers ──────────────────────────────────────────────────────

  private record FeaturesSnapshot(
      String mood, int energy, String language, String themes, int valence, int danceability) {}

  private boolean saveFeatures(
      UUID itemId, FeaturesSnapshot s, String provider, String model) {
    Boolean saved = transactionTemplate.execute(
        status -> {
          if (featuresRepository.existsById(itemId)) return true;
          var entity = new TrackAudioFeaturesEntity();
          entity.setItem(entityManager.getReference(ItemEntity.class, itemId));
          entity.setMood(s.mood());
          entity.setEnergy((short) s.energy());
          entity.setLanguage(s.language());
          entity.setThemes(s.themes());
          entity.setValence((short) s.valence());
          entity.setDanceability((short) s.danceability());
          entity.setAnalyzedAt(OffsetDateTime.now());
          entity.setLlmProvider(provider);
          entity.setLlmModel(model);
          entity.setRawResponse(provider);
          featuresRepository.save(entity);
          return true;
        });
    return Boolean.TRUE.equals(saved);
  }

  private FeaturesSnapshot hashFallback(AudioTrackEntity track) {
    String artist = track.getAlbumArtist() != null ? track.getAlbumArtist().getName() : "";
    String title = track.getItem() != null ? track.getItem().getName() : "";
    int hash = Math.abs((artist + title).hashCode());

    String mood = MOODS[hash % MOODS.length];
    int energy = (hash % 10) + 1;
    int valence = ((hash / 10) % 10) + 1;
    int danceability = ((hash / 100) % 10) + 1;
    String language = detectLanguage(track);
    String themes = deriveThemes(mood);

    return new FeaturesSnapshot(mood, energy, language, themes, valence, danceability);
  }

  private String detectLanguage(AudioTrackEntity track) {
    String artist = track.getAlbumArtist() != null ? track.getAlbumArtist().getName() : "";
    String title = track.getItem() != null ? track.getItem().getName() : "";
    String text = artist + title;
    for (char c : text.toCharArray()) {
      if (c >= '\u0400' && c <= '\u04FF') return "ru";
      if (c >= '\u3040' && c <= '\u30FF') return "ja";
      if (c >= '\uAC00' && c <= '\uD7AF') return "ko";
      if (c >= '\u4E00' && c <= '\u9FFF') return "zh";
    }
    return "en";
  }

  private String deriveThemes(String mood) {
    return switch (mood) {
      case "happy" -> "joy,sunshine";
      case "sad" -> "loss,rain";
      case "calm" -> "nature,peace";
      case "energetic" -> "party,dance";
      case "aggressive" -> "power,rebellion";
      case "romantic" -> "love,passion";
      case "melancholic" -> "memory,longing";
      case "nostalgic" -> "childhood,past";
      default -> "life";
    };
  }
}
