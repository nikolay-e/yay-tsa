package com.yaytsa.server.domain.service.radio;

import com.yaytsa.server.domain.service.AppSettingsService;
import com.yaytsa.server.infrastructure.persistence.entity.AudioTrackEntity;
import com.yaytsa.server.infrastructure.persistence.entity.ItemEntity;
import com.yaytsa.server.infrastructure.persistence.entity.TrackAudioFeaturesEntity;
import com.yaytsa.server.infrastructure.persistence.repository.AudioTrackRepository;
import com.yaytsa.server.infrastructure.persistence.repository.TrackAudioFeaturesRepository;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TrackAnalysisService {

  private static final Logger log = LoggerFactory.getLogger(TrackAnalysisService.class);
  private static final int BATCH_SIZE = 10;
  private static final long BATCH_PAUSE_MS = 1000;

  private final TrackAudioFeaturesRepository featuresRepository;
  private final AudioTrackRepository audioTrackRepository;
  private final AppSettingsService settingsService;
  private final ClaudeLlmProvider claudeProvider;
  private final OpenAiLlmProvider openAiProvider;

  private final AtomicBoolean batchRunning = new AtomicBoolean(false);

  public TrackAnalysisService(
      TrackAudioFeaturesRepository featuresRepository,
      AudioTrackRepository audioTrackRepository,
      AppSettingsService settingsService,
      ClaudeLlmProvider claudeProvider,
      OpenAiLlmProvider openAiProvider) {
    this.featuresRepository = featuresRepository;
    this.audioTrackRepository = audioTrackRepository;
    this.settingsService = settingsService;
    this.claudeProvider = claudeProvider;
    this.openAiProvider = openAiProvider;
  }

  public record AnalysisStats(long total, long analyzed, long unanalyzed, boolean batchRunning) {}

  public AnalysisStats getStats() {
    long total = featuresRepository.countTotalTracks();
    long analyzed = featuresRepository.countAnalyzed();
    return new AnalysisStats(total, analyzed, total - analyzed, batchRunning.get());
  }

  @Transactional
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

    LlmAnalysisProvider provider = getActiveProvider();
    if (provider == null) {
      log.warn("No LLM provider configured or enabled");
      return false;
    }

    AudioTrackEntity track = trackOpt.get();
    LlmAnalysisProvider.TrackContext context = buildContext(track);

    log.info(
        "Analyzing track: {} - {} (provider: {})",
        context.artist(),
        context.title(),
        provider.getProviderName());

    var result = provider.analyzeTrack(context);
    if (result.isEmpty()) {
      log.warn("LLM analysis returned empty for {} - {}", context.artist(), context.title());
      return false;
    }

    var analysis = result.get();
    var entity = new TrackAudioFeaturesEntity();
    entity.setItem(track.getItem());
    entity.setMood(analysis.mood());
    entity.setEnergy((short) analysis.energy());
    entity.setLanguage(analysis.language());
    entity.setThemes(analysis.themes());
    entity.setValence((short) analysis.valence());
    entity.setDanceability((short) analysis.danceability());
    entity.setAnalyzedAt(OffsetDateTime.now());
    entity.setLlmProvider(provider.getProviderName());
    entity.setLlmModel(provider.getModelName());
    entity.setRawResponse(analysis.rawResponse());

    featuresRepository.save(entity);
    log.info(
        "Analyzed {} - {}: mood={}, energy={}, lang={}",
        context.artist(),
        context.title(),
        analysis.mood(),
        analysis.energy(),
        analysis.language());
    return true;
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
            while (batchRunning.get()) {
              List<UUID> unanalyzed =
                  featuresRepository.findUnanalyzedTrackIdsNative(BATCH_SIZE);
              if (unanalyzed.isEmpty()) {
                log.info("Batch analysis complete: no more unanalyzed tracks");
                break;
              }

              for (UUID itemId : unanalyzed) {
                if (!batchRunning.get()) break;
                try {
                  analyzeTrack(itemId);
                } catch (Exception e) {
                  log.warn("Failed to analyze track {}: {}", itemId, e.getMessage());
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
            Thread.sleep(2000); // Wait for DB commit to propagate
            analyzeTrack(itemId);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          } catch (Exception e) {
            log.warn("Async analysis failed for track {}: {}", itemId, e.getMessage());
          }
        });
  }

  LlmAnalysisProvider getActiveProvider() {
    String providerName = settingsService.get("radio.llm.provider");
    if ("openai".equalsIgnoreCase(providerName) && openAiProvider.isEnabled()) {
      return openAiProvider;
    }
    if ("claude".equalsIgnoreCase(providerName) && claudeProvider.isEnabled()) {
      return claudeProvider;
    }
    // Auto-detect: prefer whichever is configured
    if (claudeProvider.isEnabled()) return claudeProvider;
    if (openAiProvider.isEnabled()) return openAiProvider;
    return null;
  }

  private LlmAnalysisProvider.TrackContext buildContext(AudioTrackEntity track) {
    String artist = "Unknown";
    if (track.getAlbumArtist() != null) {
      artist = track.getAlbumArtist().getName();
    }

    String title = "Unknown";
    if (track.getItem() != null) {
      title = track.getItem().getName();
    }

    String album = null;
    if (track.getAlbum() != null) {
      album = track.getAlbum().getName();
    }

    String genre = null;
    ItemEntity item = track.getItem();
    if (item != null && item.getItemGenres() != null && !item.getItemGenres().isEmpty()) {
      genre =
          item.getItemGenres().stream()
              .map(ig -> ig.getGenre().getName())
              .reduce((a, b) -> a + ", " + b)
              .orElse(null);
    }

    return new LlmAnalysisProvider.TrackContext(
        artist, title, album, track.getYear(), genre, track.getLyrics());
  }
}
