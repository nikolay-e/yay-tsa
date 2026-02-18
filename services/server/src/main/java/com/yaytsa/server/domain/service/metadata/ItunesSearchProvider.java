package com.yaytsa.server.domain.service.metadata;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * iTunes Search API metadata provider.
 *
 * <p>Queries Apple's iTunes Search API for track information. No API key required, always enabled.
 *
 * <p>API reference: https://developer.apple.com/library/archive/documentation/AudioVideo/Conceptual/iTuneSearchAPI
 */
@Component
public class ItunesSearchProvider implements MetadataProvider {

  private static final Logger log = LoggerFactory.getLogger(ItunesSearchProvider.class);

  private static final String API_BASE = "https://itunes.apple.com";
  private static final Duration TIMEOUT = Duration.ofSeconds(8);

  private final RestTemplate restTemplate;

  public ItunesSearchProvider(RestTemplateBuilder restTemplateBuilder, ObjectMapper objectMapper) {
    // iTunes Search API returns Content-Type: text/javascript — add a converter that accepts it.
    MappingJackson2HttpMessageConverter converter =
        new MappingJackson2HttpMessageConverter(objectMapper);
    converter.setSupportedMediaTypes(
        List.of(
            MediaType.APPLICATION_JSON,
            new MediaType("text", "javascript"),
            MediaType.TEXT_PLAIN));

    this.restTemplate =
        restTemplateBuilder
            .setConnectTimeout(TIMEOUT)
            .setReadTimeout(TIMEOUT)
            .defaultHeader("User-Agent", "Yay-Tsa/0.1.0")
            .additionalMessageConverters(converter)
            .build();
  }

  @Override
  @Cacheable(value = "metadata-itunes", key = "#artist + ':' + #title")
  public Optional<EnrichedMetadata> findMetadata(String artist, String title) {
    try {
      log.debug("Querying iTunes Search for: {} - {}", artist, title);

      String url =
          UriComponentsBuilder.fromHttpUrl(API_BASE + "/search")
              .queryParam("term", artist + " " + title)
              .queryParam("media", "music")
              .queryParam("limit", "5")
              .build()
              .toUriString();

      ItunesSearchResponse response = restTemplate.getForObject(url, ItunesSearchResponse.class);

      if (response == null || response.results == null || response.results.isEmpty()) {
        log.debug("No results from iTunes for: {} - {}", artist, title);
        return Optional.empty();
      }

      for (ItunesResult result : response.results) {
        double confidence = calculateConfidence(artist, title, result);

        if (confidence >= 0.70) {
          Integer year = extractYear(result.releaseDate);
          String coverArtUrl = upgradeCoverArt(result.artworkUrl100);

          log.info(
              "iTunes match: {} - {} → {} ({}) [confidence: {}]",
              artist,
              title,
              result.collectionName,
              year,
              confidence);

          // Cover art from Apple CDN is copyrighted — don't download automatically on public servers.
          // Use embedded artwork from the audio file or Cover Art Archive (CC-licensed) instead.
          return Optional.of(
              new EnrichedMetadata(
                  result.artistName,
                  result.collectionName,
                  year,
                  result.primaryGenreName,
                  null,
                  null,
                  null,
                  result.trackCount,
                  confidence,
                  getProviderName()));
        }
      }

      log.debug("No high-confidence match from iTunes");
      return Optional.empty();

    } catch (RestClientException e) {
      log.warn("iTunes Search query failed: {}", e.getMessage());
      return Optional.empty();
    } catch (Exception e) {
      log.error("Unexpected error querying iTunes", e);
      return Optional.empty();
    }
  }

  @Override
  public String getProviderName() {
    return "iTunes";
  }

  @Override
  public boolean isEnabled() {
    return true; // Always enabled, no API key required
  }

  @Override
  public int getPriority() {
    return 65; // Between MusicBrainz (60) and Last.fm (70)
  }

  private double calculateConfidence(String originalArtist, String originalTitle, ItunesResult result) {
    double artistScore = 0.0;
    double titleScore = 0.0;

    if (result.artistName != null) {
      String itunesArtist = result.artistName.toLowerCase();
      String searchArtist = originalArtist.toLowerCase();

      if (itunesArtist.equals(searchArtist)) {
        artistScore = 1.0;
      } else if (itunesArtist.contains(searchArtist) || searchArtist.contains(itunesArtist)) {
        artistScore = 0.8;
      } else {
        artistScore = 0.4;
      }
    }

    if (result.trackName != null) {
      String itunesTitle = normalizeTitle(result.trackName);
      String searchTitle = normalizeTitle(originalTitle);

      if (itunesTitle.equals(searchTitle)) {
        titleScore = 1.0;
      } else if (itunesTitle.contains(searchTitle) || searchTitle.contains(itunesTitle)) {
        titleScore = 0.8;
      } else {
        titleScore = 0.4;
      }
    }

    return Math.min(1.0, (artistScore * 0.4) + (titleScore * 0.6));
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

  private String upgradeCoverArt(String artworkUrl100) {
    if (artworkUrl100 == null) return null;
    return artworkUrl100.replace("100x100bb", "600x600bb");
  }

  // API response DTOs
  @JsonIgnoreProperties(ignoreUnknown = true)
  record ItunesSearchResponse(
      @JsonProperty("resultCount") Integer resultCount,
      @JsonProperty("results") List<ItunesResult> results) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  record ItunesResult(
      @JsonProperty("artistName") String artistName,
      @JsonProperty("trackName") String trackName,
      @JsonProperty("collectionName") String collectionName,
      @JsonProperty("primaryGenreName") String primaryGenreName,
      @JsonProperty("artworkUrl100") String artworkUrl100,
      @JsonProperty("releaseDate") String releaseDate,
      @JsonProperty("trackCount") Integer trackCount) {}
}
