package com.yaytsa.server.infrastructure.fs;

import java.util.List;

public record AudioMetadata(
    String title,
    String artist,
    String albumArtist,
    String album,
    Integer trackNumber,
    Integer discNumber,
    Integer year,
    Long durationMs,
    Integer bitrate,
    Integer sampleRate,
    Integer channels,
    String codec,
    String comment,
    String lyrics,
    List<String> genres,
    byte[] embeddedArtwork,
    String artworkMimeType) {

  private static final int DEFAULT_CHANNELS = 2;
  private static final String UNKNOWN_CODEC = "unknown";

  public static AudioMetadata fallback(String title) {
    return new AudioMetadata(
        title,
        null,
        null,
        null,
        null,
        null,
        null,
        0L,
        0,
        0,
        DEFAULT_CHANNELS,
        UNKNOWN_CODEC,
        null,
        null,
        List.of(),
        null,
        null);
  }
}
