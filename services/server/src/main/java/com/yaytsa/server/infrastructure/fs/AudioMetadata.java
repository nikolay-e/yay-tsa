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
    String artworkMimeType) {}
