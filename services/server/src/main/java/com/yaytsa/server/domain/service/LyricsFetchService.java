package com.yaytsa.server.domain.service;

import com.yaytsa.server.infrastructure.client.LyricsFetcherClient;
import com.yaytsa.server.infrastructure.client.LyricsFetcherClient.LyricsResult;
import com.yaytsa.server.infrastructure.persistence.entity.AudioTrackEntity;
import com.yaytsa.server.infrastructure.persistence.repository.AudioTrackRepository;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

@Service
public class LyricsFetchService {

  private static final Logger log = LoggerFactory.getLogger(LyricsFetchService.class);

  private final LyricsFetcherClient client;
  private final AudioTrackRepository audioTrackRepository;
  private final boolean fetchOnScan;
  private final AtomicBoolean fetchInProgress = new AtomicBoolean(false);

  private volatile FetchStats lastRunStats;

  public record FetchStats(
      int total,
      int fetched,
      int skipped,
      int failed,
      OffsetDateTime startedAt,
      OffsetDateTime completedAt) {}

  public LyricsFetchService(
      LyricsFetcherClient client,
      AudioTrackRepository audioTrackRepository,
      @Value("${yaytsa.media.lyrics.fetch-on-scan:true}") boolean fetchOnScan) {
    this.client = client;
    this.audioTrackRepository = audioTrackRepository;
    this.fetchOnScan = fetchOnScan;
  }

  public boolean isFetchOnScanEnabled() {
    return fetchOnScan;
  }

  public boolean isFetchInProgress() {
    return fetchInProgress.get();
  }

  public FetchStats getLastRunStats() {
    return lastRunStats;
  }

  public boolean fetchMissingLyrics() {
    if (!fetchInProgress.compareAndSet(false, true)) {
      log.warn("Lyrics fetch already in progress, skipping");
      return false;
    }

    OffsetDateTime startedAt = OffsetDateTime.now();
    log.info("Starting lyrics fetch for tracks with missing .lrc files");

    try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
      if (!client.isHealthy()) {
        log.warn("Lyrics fetcher service is not healthy, skipping lyrics fetch");
        lastRunStats = new FetchStats(0, 0, 0, 0, startedAt, OffsetDateTime.now());
        return false;
      }

      List<AudioTrackEntity> tracks = audioTrackRepository.findAllWithRelations();

      AtomicInteger total = new AtomicInteger(0);
      AtomicInteger fetched = new AtomicInteger(0);
      AtomicInteger skipped = new AtomicInteger(0);
      AtomicInteger failed = new AtomicInteger(0);

      var futures =
          tracks.stream()
              .filter(track -> track.getItem() != null && track.getItem().getPath() != null)
              .filter(track -> hasArtistName(track))
              .map(
                  track ->
                      java.util.concurrent.CompletableFuture.runAsync(
                          () -> processTrack(track, total, fetched, skipped, failed), executor))
              .toList();

      futures.forEach(f -> f.join());

      lastRunStats =
          new FetchStats(
              total.get(),
              fetched.get(),
              skipped.get(),
              failed.get(),
              startedAt,
              OffsetDateTime.now());

      log.info(
          "Lyrics fetch completed: total={}, fetched={}, skipped={}, failed={}",
          total.get(),
          fetched.get(),
          skipped.get(),
          failed.get());

      return true;
    } catch (Exception e) {
      log.error("Lyrics fetch failed unexpectedly", e);
      lastRunStats = new FetchStats(0, 0, 0, 1, startedAt, OffsetDateTime.now());
      return false;
    } finally {
      fetchInProgress.set(false);
    }
  }

  public boolean refetchBrokenLyrics() {
    if (!fetchInProgress.compareAndSet(false, true)) {
      log.warn("Lyrics fetch already in progress, skipping refetch");
      return false;
    }

    OffsetDateTime startedAt = OffsetDateTime.now();
    log.info("Starting lyrics refetch for tracks with broken/missing lyrics");

    try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
      if (!client.isHealthy()) {
        log.warn("Lyrics fetcher service is not healthy, skipping lyrics refetch");
        lastRunStats = new FetchStats(0, 0, 0, 0, startedAt, OffsetDateTime.now());
        return false;
      }

      List<AudioTrackEntity> tracks = audioTrackRepository.findAllWithRelations();

      AtomicInteger total = new AtomicInteger(0);
      AtomicInteger fetched = new AtomicInteger(0);
      AtomicInteger skipped = new AtomicInteger(0);
      AtomicInteger failed = new AtomicInteger(0);

      var futures =
          tracks.stream()
              .filter(track -> track.getItem() != null && track.getItem().getPath() != null)
              .filter(this::hasArtistName)
              .map(
                  track ->
                      java.util.concurrent.CompletableFuture.runAsync(
                          () -> processTrackForRefetch(track, total, fetched, skipped, failed),
                          executor))
              .toList();

      futures.forEach(f -> f.join());

      lastRunStats =
          new FetchStats(
              total.get(),
              fetched.get(),
              skipped.get(),
              failed.get(),
              startedAt,
              OffsetDateTime.now());

      log.info(
          "Lyrics refetch completed: total={}, refetched={}, skipped={}, failed={}",
          total.get(),
          fetched.get(),
          skipped.get(),
          failed.get());

      return true;
    } catch (Exception e) {
      log.error("Lyrics refetch failed unexpectedly", e);
      lastRunStats = new FetchStats(0, 0, 0, 1, startedAt, OffsetDateTime.now());
      return false;
    } finally {
      fetchInProgress.set(false);
    }
  }

  private void processTrackForRefetch(
      AudioTrackEntity track,
      AtomicInteger total,
      AtomicInteger fetched,
      AtomicInteger skipped,
      AtomicInteger failed) {
    total.incrementAndGet();

    try {
      Path trackPath = Path.of(track.getItem().getPath());
      Path lyricsDir = trackPath.getParent().resolve(".lyrics");
      Path lrcPath =
          lyricsDir.resolve(trackPath.getFileName().toString().replaceFirst("\\.[^.]+$", ".lrc"));

      boolean needsRefetch = false;

      if (!Files.exists(lrcPath) || Files.size(lrcPath) == 0) {
        needsRefetch = true;
      } else {
        String content = Files.readString(lrcPath).trim();
        if ("[no lyrics found]".equals(content)) {
          needsRefetch = true;
        } else if (countLyricLines(content) < 2) {
          needsRefetch = true;
        } else if (content.contains("<html") || content.contains("<body")) {
          needsRefetch = true;
        }
      }

      if (!needsRefetch) {
        skipped.incrementAndGet();
        return;
      }

      String artist = resolveArtistName(track);
      String title = track.getItem().getName();
      Long durationMs = track.getDurationMs();
      String album = track.getAlbum() != null ? track.getAlbum().getName() : null;

      LyricsResult result =
          client.fetchLyrics(artist, title, lrcPath.toString(), durationMs, album, true);

      if (result.success()) {
        fetched.incrementAndGet();
        log.debug("Refetched lyrics for: {} - {}", artist, title);
      } else {
        failed.incrementAndGet();
      }
    } catch (Exception e) {
      failed.incrementAndGet();
      log.debug("Error refetching lyrics for track {}: {}", track.getItemId(), e.getMessage());
    }
  }

  private int countLyricLines(String content) {
    int count = 0;
    for (String line : content.split("\n")) {
      String trimmed = line.trim();
      if (trimmed.isEmpty()) continue;
      if (trimmed.matches("^\\[\\d+:\\d{2}.*")) {
        String text = trimmed.replaceAll("^\\[\\d+:\\d{2}(?:\\.\\d{2,3})?\\]\\s*", "");
        if (!text.isEmpty()) count++;
      } else if (!trimmed.startsWith("[")) {
        count++;
      }
    }
    return count;
  }

  private void processTrack(
      AudioTrackEntity track,
      AtomicInteger total,
      AtomicInteger fetched,
      AtomicInteger skipped,
      AtomicInteger failed) {
    total.incrementAndGet();

    try {
      Path trackPath = Path.of(track.getItem().getPath());
      Path lyricsDir = trackPath.getParent().resolve(".lyrics");
      Path lrcPath =
          lyricsDir.resolve(trackPath.getFileName().toString().replaceFirst("\\.[^.]+$", ".lrc"));

      if (Files.exists(lrcPath) && Files.size(lrcPath) > 0) {
        skipped.incrementAndGet();
        return;
      }

      String artist = resolveArtistName(track);
      String title = track.getItem().getName();
      Long durationMs = track.getDurationMs();
      String album = track.getAlbum() != null ? track.getAlbum().getName() : null;

      LyricsResult result =
          client.fetchLyrics(artist, title, lrcPath.toString(), durationMs, album, false);

      if (result.success()) {
        fetched.incrementAndGet();
        log.debug("Fetched lyrics for: {} - {}", artist, title);
      } else {
        failed.incrementAndGet();
      }
    } catch (Exception e) {
      failed.incrementAndGet();
      log.debug("Error processing lyrics for track {}: {}", track.getItemId(), e.getMessage());
    }
  }

  @Async
  public void fetchLyricsForTrackAsync(AudioTrackEntity track) {
    if (track.getItem() == null || track.getItem().getPath() == null || !hasArtistName(track)) {
      return;
    }

    try {
      Path trackPath = Path.of(track.getItem().getPath());
      Path lyricsDir = trackPath.getParent().resolve(".lyrics");
      Path lrcPath =
          lyricsDir.resolve(trackPath.getFileName().toString().replaceFirst("\\.[^.]+$", ".lrc"));

      if (Files.exists(lrcPath) && Files.size(lrcPath) > 0) {
        return;
      }

      String artist = resolveArtistName(track);
      String title = track.getItem().getName();
      Long durationMs = track.getDurationMs();
      String album = track.getAlbum() != null ? track.getAlbum().getName() : null;

      log.info("On-demand lyrics fetch for: {} - {}", artist, title);
      LyricsResult result =
          client.fetchLyrics(artist, title, lrcPath.toString(), durationMs, album, false);

      if (result.success()) {
        log.info(
            "On-demand lyrics fetched for: {} - {} (source: {})", artist, title, result.source());
      }
    } catch (Exception e) {
      log.debug(
          "On-demand lyrics fetch failed for track {}: {}", track.getItemId(), e.getMessage());
    }
  }

  private boolean hasArtistName(AudioTrackEntity track) {
    return (track.getAlbumArtist() != null && track.getAlbumArtist().getName() != null)
        || (track.getAlbum() != null
            && track.getAlbum().getParent() != null
            && track.getAlbum().getParent().getName() != null);
  }

  private String resolveArtistName(AudioTrackEntity track) {
    if (track.getAlbumArtist() != null && track.getAlbumArtist().getName() != null) {
      return track.getAlbumArtist().getName();
    }
    if (track.getAlbum() != null && track.getAlbum().getParent() != null) {
      return track.getAlbum().getParent().getName();
    }
    return "Unknown Artist";
  }
}
