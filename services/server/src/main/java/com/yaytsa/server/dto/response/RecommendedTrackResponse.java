package com.yaytsa.server.dto.response;

import java.util.UUID;

public record RecommendedTrackResponse(
    UUID trackId,
    String name,
    String artistName,
    String albumName,
    long durationMs,
    double score,
    String source,
    TrackFeaturesSnippet features) {

  public record TrackFeaturesSnippet(
      Float bpm, Float energy, Float valence, Float arousal, Float danceability) {}
}
