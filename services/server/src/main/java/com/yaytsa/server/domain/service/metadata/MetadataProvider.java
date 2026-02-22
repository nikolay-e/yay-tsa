package com.yaytsa.server.domain.service.metadata;

import java.util.Optional;

/**
 * Interface for metadata enrichment providers.
 *
 * <p>Implementations query external APIs (MusicBrainz, Last.fm, Spotify, etc.) to find missing
 * track metadata based on artist and title.
 */
public interface MetadataProvider {

  /**
   * Enriched metadata result from external API.
   *
   * @param artist Album artist name
   * @param album Album title
   * @param year Release year
   * @param genre Primary genre
   * @param coverArtUrl URL to album cover art (optional)
   * @param artistImageUrl URL to artist image (optional)
   * @param lyrics Song lyrics in plain text (optional)
   * @param totalTracks Total number of tracks on the album (optional, for completion tracking)
   * @param confidence Confidence score (0.0-1.0) based on match quality
   * @param source Provider name (e.g., "MusicBrainz", "Last.fm")
   */
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
      return new EnrichedMetadata(artist, album, year, genre, coverArtUrl, artistImageUrl, lyrics, totalTracks, newConfidence, source);
    }

    public EnrichedMetadata withCoverArtUrl(String newCoverArtUrl) {
      return new EnrichedMetadata(artist, album, year, genre, newCoverArtUrl, artistImageUrl, lyrics, totalTracks, confidence, source);
    }
  }

  /**
   * Attempts to enrich metadata by querying external API.
   *
   * @param artist Artist name from file tags
   * @param title Track title from file tags
   * @return Enriched metadata if found, empty otherwise
   */
  Optional<EnrichedMetadata> findMetadata(String artist, String title);

  /**
   * Provider name for logging and debugging.
   *
   * @return Provider identifier (e.g., "MusicBrainz", "Spotify")
   */
  String getProviderName();

  /**
   * Whether this provider is enabled and configured.
   *
   * @return true if provider can be used
   */
  boolean isEnabled();

  /**
   * Priority for this provider (higher = checked first).
   *
   * <p>Default priority: 50. Higher values are preferred when multiple providers return results.
   *
   * @return Priority value (0-100)
   */
  default int getPriority() {
    return 50;
  }
}
