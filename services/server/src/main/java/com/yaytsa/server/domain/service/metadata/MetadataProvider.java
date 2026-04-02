package com.yaytsa.server.domain.service.metadata;

import java.util.Optional;

public interface MetadataProvider {

  record EnrichedMetadata(
      String artist,
      String album,
      Integer year,
      String genre,
      String coverArtUrl,
      String artistImageUrl,
      String lyrics,
      Integer totalTracks,
      double confidence,
      String source) {

    public EnrichedMetadata withConfidence(double newConfidence) {
      return new EnrichedMetadata(
          artist,
          album,
          year,
          genre,
          coverArtUrl,
          artistImageUrl,
          lyrics,
          totalTracks,
          newConfidence,
          source);
    }

    public EnrichedMetadata withCoverArtUrl(String newCoverArtUrl) {
      return new EnrichedMetadata(
          artist,
          album,
          year,
          genre,
          newCoverArtUrl,
          artistImageUrl,
          lyrics,
          totalTracks,
          confidence,
          source);
    }
  }

  Optional<EnrichedMetadata> findMetadata(String artist, String title);

  String getProviderName();

  boolean isEnabled();

  default int getPriority() {
    return 50;
  }
}
