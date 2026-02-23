package com.yaytsa.server.domain.service.radio;

import java.util.Optional;

public interface LlmAnalysisProvider {

  record TrackAnalysis(
      String mood,
      int energy,
      String language,
      String themes,
      int valence,
      int danceability,
      String rawResponse) {}

  record TrackContext(
      String artist,
      String title,
      String album,
      Integer year,
      String genre,
      String lyrics) {}

  Optional<TrackAnalysis> analyzeTrack(TrackContext context);

  String getProviderName();

  String getModelName();

  boolean isEnabled();
}
