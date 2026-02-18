package com.yaytsa.server.domain.service;

import com.yaytsa.server.infrastructure.client.LrcLibClient;
import com.yaytsa.server.infrastructure.client.LrcLibClient.LrcLibResult;
import com.yaytsa.server.infrastructure.persistence.entity.AudioTrackEntity;
import com.yaytsa.server.infrastructure.persistence.repository.AudioTrackRepository;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

@Service
public class LyricsFetchService {

  private static final Logger log = LoggerFactory.getLogger(LyricsFetchService.class);

  private final LrcLibClient lrcLibClient;
  private final AudioTrackRepository audioTrackRepository;

  public record OnDemandLyricsResult(boolean found, String lyrics, String source) {}

  public LyricsFetchService(LrcLibClient lrcLibClient, AudioTrackRepository audioTrackRepository) {
    this.lrcLibClient = lrcLibClient;
    this.audioTrackRepository = audioTrackRepository;
  }

  public OnDemandLyricsResult fetchLyricsForTrack(UUID trackId) {
    AudioTrackEntity track =
        audioTrackRepository
            .findByIdWithRelations(trackId)
            .orElseThrow(() -> new IllegalArgumentException("Track not found: " + trackId));

    if (track.getItem() == null || track.getItem().getPath() == null || !hasArtistName(track)) {
      return new OnDemandLyricsResult(false, null, null);
    }

    String artist = resolveArtistName(track);
    String title = track.getItem().getName();
    Long durationMs = track.getDurationMs();
    String album = track.getAlbum() != null ? track.getAlbum().getName() : null;

    log.info("On-demand lyrics fetch for: {} - {}", artist, title);
    return fetchAndSave(track, artist, title, album, durationMs);
  }

  @Async
  public void fetchLyricsForTrackAsync(AudioTrackEntity track) {
    if (track.getItem() == null || track.getItem().getPath() == null || !hasArtistName(track)) {
      return;
    }

    // Skip if lyrics already cached in DB
    if (track.getLyrics() != null && !track.getLyrics().isBlank()) {
      return;
    }

    // Skip if .lrc file already exists on disk
    try {
      Path trackPath = Path.of(track.getItem().getPath());
      Path lrcPath =
          trackPath
              .getParent()
              .resolve(".lyrics")
              .resolve(trackPath.getFileName().toString().replaceFirst("\\.[^.]+$", ".lrc"));
      if (Files.exists(lrcPath)) {
        return;
      }
    } catch (Exception ignored) {
      // path issues — proceed with fetch attempt
    }

    try {
      String artist = resolveArtistName(track);
      String title = track.getItem().getName();
      Long durationMs = track.getDurationMs();
      String album = track.getAlbum() != null ? track.getAlbum().getName() : null;

      log.info("Background lyrics fetch for: {} - {}", artist, title);
      OnDemandLyricsResult result = fetchAndSave(track, artist, title, album, durationMs);
      if (result.found()) {
        log.info(
            "Background lyrics fetched for: {} - {} (source: {})",
            artist,
            title,
            result.source());
      }
    } catch (Exception e) {
      log.debug(
          "Background lyrics fetch failed for track {}: {}", track.getItemId(), e.getMessage());
    }
  }

  private OnDemandLyricsResult fetchAndSave(
      AudioTrackEntity track, String artist, String title, String album, Long durationMs) {

    LrcLibResult result = lrcLibClient.fetchLyrics(artist, title, album, durationMs);

    if (!result.found()) {
      return new OnDemandLyricsResult(false, null, null);
    }

    // Prefer synced LRC, fall back to plain text
    boolean synced = result.syncedLyrics() != null && !result.syncedLyrics().isBlank();
    String lyrics = synced ? result.syncedLyrics() : result.plainLyrics();

    // Persist to DB (targeted update to avoid detached entity issues)
    audioTrackRepository.updateLyrics(track.getItemId(), lyrics);

    // Also write to disk so LyricsService disk reader picks it up
    if (track.getItem() != null && track.getItem().getPath() != null) {
      try {
        Path trackPath = Path.of(track.getItem().getPath());
        Path lyricsDir = trackPath.getParent().resolve(".lyrics");
        String ext = synced ? ".lrc" : ".txt";
        Path lrcPath =
            lyricsDir.resolve(
                trackPath.getFileName().toString().replaceFirst("\\.[^.]+$", ext));
        Files.createDirectories(lyricsDir);
        Files.writeString(lrcPath, lyrics);
        log.debug("Saved lyrics to disk: {}", lrcPath);
      } catch (IOException e) {
        log.warn("Failed to save lyrics to disk for track {}: {}", track.getItemId(), e.getMessage());
      }
    }

    return new OnDemandLyricsResult(true, lyrics, "lrclib");
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
