package com.yaytsa.server.domain.service.metadata;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * MusicBrainz metadata provider.
 *
 * <p>Queries the MusicBrainz database (open music encyclopedia) for track metadata. No API key
 * required, but rate-limited to 1 request per second.
 */
@Component
public class MusicBrainzProvider implements MetadataProvider {

  private static final Logger log = LoggerFactory.getLogger(MusicBrainzProvider.class);

  private static final String API_BASE = "https://musicbrainz.org/ws/2";
  private static final String USER_AGENT = "Yay-Tsa/0.1.0 (https://github.com/yay-tsa)";
  private static final Duration TIMEOUT = Duration.ofSeconds(10);
  private static final long MIN_REQUEST_INTERVAL_MS = 1100; // Rate limit: 1 req/sec

  private final RestTemplate restTemplate;
  private long lastRequestTime = 0;

  public MusicBrainzProvider(RestTemplateBuilder restTemplateBuilder) {
    this.restTemplate =
        restTemplateBuilder
            .setConnectTimeout(TIMEOUT)
            .setReadTimeout(TIMEOUT)
            .defaultHeader("User-Agent", USER_AGENT)
            .build();
  }

  @Override
  @Cacheable(value = "metadata-musicbrainz", key = "#artist + ':' + #title")
  public Optional<EnrichedMetadata> findMetadata(String artist, String title) {
    if (!isEnabled()) {
      return Optional.empty();
    }

    try {
      enforceRateLimit();

      log.debug("Querying MusicBrainz for: {} - {}", artist, title);

      String query =
          String.format("artist:\"%s\" AND recording:\"%s\"", escape(artist), escape(title));
      String url =
          UriComponentsBuilder.fromHttpUrl(API_BASE + "/recording")
              .queryParam("query", query)
              .queryParam("fmt", "json")
              .queryParam("limit", "5")
              .build()
              .toUriString();

      MusicBrainzResponse response = restTemplate.getForObject(url, MusicBrainzResponse.class);

      if (response == null || response.recordings == null || response.recordings.isEmpty()) {
        log.debug("No results from MusicBrainz for: {} - {}", artist, title);
        return Optional.empty();
      }

      for (MusicBrainzRecording recording : response.recordings) {
        if (recording.releases == null || recording.releases.isEmpty()) {
          continue;
        }

        double confidence = calculateConfidence(artist, title, recording);

        if (confidence >= 0.70) {
          MusicBrainzRelease release = recording.releases.get(0);
          String albumArtist =
              recording.artistCredit != null && !recording.artistCredit.isEmpty()
                  ? recording.artistCredit.get(0).name
                  : artist;

          Integer year = extractYear(release.date);

          log.info(
              "MusicBrainz match: {} - {} → {} ({}) [confidence: {}]",
              artist,
              title,
              release.title,
              year,
              confidence);

          // Cover Art Archive: CC-licensed artwork, safe for public servers
          String coverArtUrl = "https://coverartarchive.org/release/" + release.id + "/front-500";

          return Optional.of(
              new EnrichedMetadata(
                  albumArtist, release.title, year, null, coverArtUrl, null, null, null, confidence, getProviderName()));
        }
      }

      log.debug("No high-confidence match from MusicBrainz");
      return Optional.empty();

    } catch (RestClientException e) {
      log.warn("MusicBrainz query failed: {}", e.getMessage());
      return Optional.empty();
    } catch (Exception e) {
      log.error("Unexpected error querying MusicBrainz", e);
      return Optional.empty();
    }
  }

  @Override
  public String getProviderName() {
    return "MusicBrainz";
  }

  @Override
  public boolean isEnabled() {
    return true; // Always enabled (no API key required)
  }

  @Override
  public int getPriority() {
    return 60; // Slightly higher priority (open database, good quality)
  }

  private synchronized void enforceRateLimit() {
    long now = System.currentTimeMillis();
    long timeSinceLastRequest = now - lastRequestTime;

    if (timeSinceLastRequest < MIN_REQUEST_INTERVAL_MS) {
      long sleepTime = MIN_REQUEST_INTERVAL_MS - timeSinceLastRequest;
      try {
        Thread.sleep(sleepTime);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }

    lastRequestTime = System.currentTimeMillis();
  }

  private double calculateConfidence(
      String originalArtist, String originalTitle, MusicBrainzRecording recording) {
    double artistScore = 0.0;
    double titleScore = 0.0;

    if (recording.artistCredit != null && !recording.artistCredit.isEmpty()) {
      String mbArtist = recording.artistCredit.get(0).name.toLowerCase();
      String searchArtist = originalArtist.toLowerCase();

      if (mbArtist.equals(searchArtist)) {
        artistScore = 1.0;
      } else if (mbArtist.contains(searchArtist) || searchArtist.contains(mbArtist)) {
        artistScore = 0.8;
      } else {
        artistScore = 0.5;
      }
    }

    if (recording.title != null) {
      String mbTitle = normalizeTitle(recording.title);
      String searchTitle = normalizeTitle(originalTitle);

      if (mbTitle.equals(searchTitle)) {
        titleScore = 1.0;
      } else if (mbTitle.contains(searchTitle) || searchTitle.contains(mbTitle)) {
        titleScore = 0.8;
      } else {
        titleScore = 0.5;
      }
    }

    return (artistScore * 0.4) + (titleScore * 0.6);
  }

  private String normalizeTitle(String title) {
    return title
        .toLowerCase()
        .replaceAll("\\([^)]*\\)", "")
        .replaceAll("\\[[^]]*\\]", "")
        .replaceAll("[\\-_]", " ")
        .replaceAll("\\s+", " ")
        .trim();
  }

  private Integer extractYear(String date) {
    if (date != null && date.length() >= 4) {
      try {
        return Integer.parseInt(date.substring(0, 4));
      } catch (NumberFormatException e) {
        return null;
      }
    }
    return null;
  }

  private String escape(String query) {
    return query.replace("\"", "\\\"").replace(":", "\\:");
  }

  // API response DTOs
  @JsonIgnoreProperties(ignoreUnknown = true)
  record MusicBrainzResponse(@JsonProperty("recordings") List<MusicBrainzRecording> recordings) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  record MusicBrainzRecording(
      String id,
      String title,
      @JsonProperty("artist-credit") List<MusicBrainzArtistCredit> artistCredit,
      List<MusicBrainzRelease> releases) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  record MusicBrainzArtistCredit(String name) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  record MusicBrainzRelease(String id, String title, String date) {}
}
