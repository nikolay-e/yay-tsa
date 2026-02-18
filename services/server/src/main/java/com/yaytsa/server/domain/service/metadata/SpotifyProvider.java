package com.yaytsa.server.domain.service.metadata;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.yaytsa.server.domain.service.AppSettingsService;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Spotify metadata provider.
 *
 * <p>Queries Spotify Web API for track information. Requires Client ID and Client Secret (free
 * registration at https://developer.spotify.com).
 *
 * <p>Uses Client Credentials Flow for server-to-server authentication.
 */
@Component
public class SpotifyProvider implements MetadataProvider {

  private static final Logger log = LoggerFactory.getLogger(SpotifyProvider.class);

  private static final String API_BASE = "https://api.spotify.com/v1";
  private static final String AUTH_URL = "https://accounts.spotify.com/api/token";
  private static final Duration TIMEOUT = Duration.ofSeconds(10);

  private final RestTemplate restTemplate;
  private final AppSettingsService settingsService;

  private String accessToken;
  private Instant tokenExpiry;

  public SpotifyProvider(
      RestTemplateBuilder restTemplateBuilder, AppSettingsService settingsService) {
    this.restTemplate =
        restTemplateBuilder.setConnectTimeout(TIMEOUT).setReadTimeout(TIMEOUT).build();
    this.settingsService = settingsService;
  }

  private String getClientId() {
    return settingsService.get("metadata.spotify.client-id", "SPOTIFY_CLIENT_ID");
  }

  private String getClientSecret() {
    return settingsService.get("metadata.spotify.client-secret", "SPOTIFY_CLIENT_SECRET");
  }

  @Override
  @Cacheable(value = "metadata-spotify", key = "#artist + ':' + #title")
  public Optional<EnrichedMetadata> findMetadata(String artist, String title) {
    if (!isEnabled()) {
      return Optional.empty();
    }

    try {
      ensureAuthenticated();

      log.debug("Querying Spotify for: {} - {}", artist, title);

      String query = String.format("artist:%s track:%s", artist, title);
      String url =
          UriComponentsBuilder.fromHttpUrl(API_BASE + "/search")
              .queryParam("q", query)
              .queryParam("type", "track")
              .queryParam("limit", "5")
              .build()
              .toUriString();

      HttpHeaders headers = new HttpHeaders();
      headers.setBearerAuth(accessToken);
      HttpEntity<String> entity = new HttpEntity<>(headers);

      ResponseEntity<SpotifySearchResponse> responseEntity =
          restTemplate.exchange(url, HttpMethod.GET, entity, SpotifySearchResponse.class);
      SpotifySearchResponse response = responseEntity.getBody();

      if (response == null
          || response.tracks == null
          || response.tracks.items == null
          || response.tracks.items.isEmpty()) {
        log.debug("No results from Spotify for: {} - {}", artist, title);
        return Optional.empty();
      }

      for (SpotifyTrack track : response.tracks.items) {
        double confidence = calculateConfidence(artist, title, track);

        if (confidence >= 0.70 && track.album != null) {
          String albumArtist =
              track.artists != null && !track.artists.isEmpty()
                  ? track.artists.get(0).name
                  : artist;

          Integer year = extractYear(track.album.releaseDate);

          // Extract genre from album (Spotify tracks don't have genres directly)
          String genre = null;
          if (track.album.genres != null && !track.album.genres.isEmpty()) {
            genre = track.album.genres.get(0);
          }

          log.info(
              "Spotify match: {} - {} → {} ({}) [confidence: {}]",
              artist,
              title,
              track.album.name,
              year,
              confidence);

          return Optional.of(
              new EnrichedMetadata(
                  albumArtist,
                  track.album.name,
                  year,
                  genre,
                  null,
                  null,
                  null,
                  null,
                  confidence,
                  getProviderName()));
        }
      }

      log.debug("No high-confidence match from Spotify");
      return Optional.empty();

    } catch (RestClientException e) {
      log.warn("Spotify query failed: {}", e.getMessage());
      return Optional.empty();
    } catch (Exception e) {
      log.error("Unexpected error querying Spotify", e);
      return Optional.empty();
    }
  }

  @Override
  public String getProviderName() {
    return "Spotify";
  }

  @Override
  public boolean isEnabled() {
    return !getClientId().isBlank() && !getClientSecret().isBlank();
  }

  @Override
  public int getPriority() {
    return 80; // Highest priority (best coverage for modern music)
  }

  private synchronized void ensureAuthenticated() {
    if (accessToken != null && tokenExpiry != null && Instant.now().isBefore(tokenExpiry)) {
      return; // Token still valid
    }

    log.debug("Requesting new Spotify access token");

    String auth =
        Base64.getEncoder()
            .encodeToString(
                (getClientId() + ":" + getClientSecret()).getBytes(StandardCharsets.UTF_8));

    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
    headers.set("Authorization", "Basic " + auth);

    MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
    body.add("grant_type", "client_credentials");

    HttpEntity<MultiValueMap<String, String>> request = new HttpEntity<>(body, headers);

    SpotifyTokenResponse response =
        restTemplate.postForObject(AUTH_URL, request, SpotifyTokenResponse.class);

    if (response != null && response.accessToken != null) {
      this.accessToken = response.accessToken;
      this.tokenExpiry = Instant.now().plusSeconds(response.expiresIn - 60); // 60s buffer
      log.debug("Spotify access token obtained (expires in {}s)", response.expiresIn);
    } else {
      throw new IllegalStateException("Failed to obtain Spotify access token");
    }
  }

  private double calculateConfidence(String originalArtist, String originalTitle, SpotifyTrack track) {
    double artistScore = 0.0;
    double titleScore = 0.0;

    if (track.artists != null && !track.artists.isEmpty()) {
      String spotifyArtist = track.artists.get(0).name.toLowerCase();
      String searchArtist = originalArtist.toLowerCase();

      if (spotifyArtist.equals(searchArtist)) {
        artistScore = 1.0;
      } else if (spotifyArtist.contains(searchArtist) || searchArtist.contains(spotifyArtist)) {
        artistScore = 0.85;
      } else {
        artistScore = 0.5;
      }
    }

    if (track.name != null) {
      String spotifyTitle = normalizeTitle(track.name);
      String searchTitle = normalizeTitle(originalTitle);

      if (spotifyTitle.equals(searchTitle)) {
        titleScore = 1.0;
      } else if (spotifyTitle.contains(searchTitle) || searchTitle.contains(spotifyTitle)) {
        titleScore = 0.85;
      } else {
        titleScore = 0.5;
      }
    }

    // Boost for popularity (0-100 scale)
    double popularityBoost = track.popularity != null ? (track.popularity / 1000.0) : 0.0;

    return Math.min(1.0, (artistScore * 0.4) + (titleScore * 0.6) + popularityBoost);
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

  // API response DTOs
  @JsonIgnoreProperties(ignoreUnknown = true)
  record SpotifyTokenResponse(
      @JsonProperty("access_token") String accessToken,
      @JsonProperty("expires_in") int expiresIn) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  record SpotifySearchResponse(SpotifyTracks tracks) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  record SpotifyTracks(List<SpotifyTrack> items) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  record SpotifyTrack(
      String name,
      List<SpotifyArtist> artists,
      SpotifyAlbum album,
      Integer popularity) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  record SpotifyArtist(String name) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  record SpotifyAlbum(
      String name,
      @JsonProperty("release_date") String releaseDate,
      List<String> genres) {}
}
