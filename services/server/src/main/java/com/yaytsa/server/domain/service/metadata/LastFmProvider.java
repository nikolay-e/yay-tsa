package com.yaytsa.server.domain.service.metadata;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Last.fm metadata provider.
 *
 * <p>Queries Last.fm API for track information. Requires API key (free registration at
 * https://www.last.fm/api).
 */
@Component
public class LastFmProvider implements MetadataProvider {

  private static final Logger log = LoggerFactory.getLogger(LastFmProvider.class);

  private static final String API_BASE = "https://ws.audioscrobbler.com/2.0";
  private static final Duration TIMEOUT = Duration.ofSeconds(10);

  private final RestTemplate restTemplate;
  private final String apiKey;

  public LastFmProvider(
      RestTemplateBuilder restTemplateBuilder,
      @Value("${yaytsa.metadata.lastfm.api-key:}") String apiKey) {
    this.restTemplate =
        restTemplateBuilder.setConnectTimeout(TIMEOUT).setReadTimeout(TIMEOUT).build();
    this.apiKey = apiKey;
  }

  @Override
  @Cacheable(value = "metadata-lastfm", key = "#artist + ':' + #title")
  public Optional<EnrichedMetadata> findMetadata(String artist, String title) {
    if (!isEnabled()) {
      return Optional.empty();
    }

    try {
      log.debug("Querying Last.fm for: {} - {}", artist, title);

      String url =
          UriComponentsBuilder.fromHttpUrl(API_BASE)
              .queryParam("method", "track.getInfo")
              .queryParam("api_key", apiKey)
              .queryParam("artist", artist)
              .queryParam("track", title)
              .queryParam("format", "json")
              .build()
              .toUriString();

      LastFmResponse response = restTemplate.getForObject(url, LastFmResponse.class);

      if (response == null || response.track == null) {
        log.debug("No results from Last.fm for: {} - {}", artist, title);
        return Optional.empty();
      }

      LastFmTrack track = response.track;

      // Calculate confidence based on match quality
      double confidence = calculateConfidence(artist, title, track);

      if (confidence < 0.70) {
        log.debug("Low confidence match from Last.fm: {:.2f}", confidence);
        return Optional.empty();
      }

      String albumName = track.album != null ? track.album.title : null;
      String albumArtist = track.artist != null ? track.artist.name : artist;

      // Extract year from album wiki or tags
      Integer year = null;
      if (track.album != null && track.album.wiki != null) {
        year = extractYearFromText(track.album.wiki.published);
      }

      // Extract primary genre from tags
      String genre = null;
      if (track.toptags != null && track.toptags.tag != null && !track.toptags.tag.isEmpty()) {
        genre = track.toptags.tag.get(0).name;
      }

      log.info(
          "Last.fm match: {} - {} → {} ({}) [confidence: {:.2f}]",
          artist,
          title,
          albumName,
          year,
          confidence);

      return Optional.of(
          new EnrichedMetadata(
              albumArtist, albumName, year, genre, null, null, null, confidence, getProviderName()));

    } catch (RestClientException e) {
      log.warn("Last.fm query failed: {}", e.getMessage());
      return Optional.empty();
    } catch (Exception e) {
      log.error("Unexpected error querying Last.fm", e);
      return Optional.empty();
    }
  }

  @Override
  public String getProviderName() {
    return "Last.fm";
  }

  @Override
  public boolean isEnabled() {
    return apiKey != null && !apiKey.isBlank();
  }

  @Override
  public int getPriority() {
    return 70; // High priority (good coverage, popular service)
  }

  private double calculateConfidence(String originalArtist, String originalTitle, LastFmTrack track) {
    double artistScore = 0.0;
    double titleScore = 0.0;

    if (track.artist != null && track.artist.name != null) {
      String lfmArtist = track.artist.name.toLowerCase();
      String searchArtist = originalArtist.toLowerCase();

      if (lfmArtist.equals(searchArtist)) {
        artistScore = 1.0;
      } else if (lfmArtist.contains(searchArtist) || searchArtist.contains(lfmArtist)) {
        artistScore = 0.8;
      } else {
        artistScore = 0.5;
      }
    }

    if (track.name != null) {
      String lfmTitle = normalizeTitle(track.name);
      String searchTitle = normalizeTitle(originalTitle);

      if (lfmTitle.equals(searchTitle)) {
        titleScore = 1.0;
      } else if (lfmTitle.contains(searchTitle) || searchTitle.contains(lfmTitle)) {
        titleScore = 0.8;
      } else {
        titleScore = 0.5;
      }
    }

    // Boost confidence if album info is available
    double boost = track.album != null ? 0.1 : 0.0;

    return Math.min(1.0, (artistScore * 0.4) + (titleScore * 0.6) + boost);
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

  private Integer extractYearFromText(String text) {
    if (text == null) return null;
    // Try to extract 4-digit year
    java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("(19|20)\\d{2}");
    java.util.regex.Matcher matcher = pattern.matcher(text);
    if (matcher.find()) {
      try {
        return Integer.parseInt(matcher.group());
      } catch (NumberFormatException e) {
        return null;
      }
    }
    return null;
  }

  // API response DTOs
  @JsonIgnoreProperties(ignoreUnknown = true)
  record LastFmResponse(LastFmTrack track) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  record LastFmTrack(
      String name,
      LastFmArtist artist,
      LastFmAlbum album,
      @JsonProperty("toptags") LastFmTopTags toptags) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  record LastFmArtist(String name) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  record LastFmAlbum(
      String title,
      LastFmArtist artist,
      LastFmWiki wiki) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  record LastFmWiki(String published) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  record LastFmTopTags(List<LastFmTag> tag) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  record LastFmTag(String name) {}
}
