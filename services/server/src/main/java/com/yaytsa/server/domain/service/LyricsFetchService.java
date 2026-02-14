package com.yaytsa.server.domain.service;

import com.yaytsa.server.infrastructure.client.LyricsFetcherClient;
import com.yaytsa.server.infrastructure.client.LyricsFetcherClient.LyricsResult;
import com.yaytsa.server.infrastructure.persistence.entity.AudioTrackEntity;
import com.yaytsa.server.infrastructure.persistence.repository.AudioTrackRepository;
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

  private final LyricsFetcherClient client;
  private final AudioTrackRepository audioTrackRepository;

  public record OnDemandLyricsResult(boolean found, String lyrics, String source) {}

  public LyricsFetchService(LyricsFetcherClient client, AudioTrackRepository audioTrackRepository) {
    this.client = client;
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

    Path trackPath = Path.of(track.getItem().getPath());
    Path lyricsDir = trackPath.getParent().resolve(".lyrics");
    Path lrcPath =
        lyricsDir.resolve(trackPath.getFileName().toString().replaceFirst("\\.[^.]+$", ".lrc"));

    String artist = resolveArtistName(track);
    String title = track.getItem().getName();
    Long durationMs = track.getDurationMs();
    String album = track.getAlbum() != null ? track.getAlbum().getName() : null;

    log.info("On-demand lyrics fetch for: {} - {}", artist, title);
    LyricsResult result =
        client.fetchLyrics(artist, title, lrcPath.toString(), durationMs, album, true);

    if (result.success() && result.lyrics() != null) {
      log.info(
          "On-demand lyrics fetched for: {} - {} (source: {})", artist, title, result.source());
      return new OnDemandLyricsResult(true, result.lyrics(), result.source());
    }

    return new OnDemandLyricsResult(false, null, null);
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

      log.info("Background lyrics fetch for: {} - {}", artist, title);
      LyricsResult result =
          client.fetchLyrics(artist, title, lrcPath.toString(), durationMs, album, false);

      if (result.success()) {
        log.info(
            "Background lyrics fetched for: {} - {} (source: {})", artist, title, result.source());
      }
    } catch (Exception e) {
      log.debug(
          "Background lyrics fetch failed for track {}: {}", track.getItemId(), e.getMessage());
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
