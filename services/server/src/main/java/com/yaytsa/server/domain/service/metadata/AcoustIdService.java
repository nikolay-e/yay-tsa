package com.yaytsa.server.domain.service.metadata;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yaytsa.server.domain.service.AppSettingsService;
import jakarta.annotation.PreDestroy;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class AcoustIdService {

  private static final Logger log = LoggerFactory.getLogger(AcoustIdService.class);

  private static final String API_URL = "https://api.acoustid.org/v2/lookup";
  private static final Duration TIMEOUT = Duration.ofSeconds(10);

  private final HttpClient httpClient;
  private final AppSettingsService settingsService;
  private final ObjectMapper objectMapper;

  public AcoustIdService(AppSettingsService settingsService, ObjectMapper objectMapper) {
    this.httpClient = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
    this.settingsService = settingsService;
    this.objectMapper = objectMapper;
  }

  @PreDestroy
  public void close() {
    httpClient.close();
  }

  public record AcoustIdResult(
      String artist, String title, String album, Integer year, String musicbrainzRecordingId) {}

  public boolean isEnabled() {
    return !getApiKey().isBlank();
  }

  public Optional<AcoustIdResult> lookup(String fingerprint, double durationSeconds) {
    if (!isEnabled()) {
      log.debug("AcoustID lookup disabled (no API key configured)");
      return Optional.empty();
    }

    if (fingerprint == null || fingerprint.isBlank()) {
      return Optional.empty();
    }

    try {
      int duration = (int) Math.round(durationSeconds);

      String body =
          "client=" + URLEncoder.encode(getApiKey(), StandardCharsets.UTF_8)
              + "&duration=" + duration
              + "&fingerprint=" + URLEncoder.encode(fingerprint, StandardCharsets.UTF_8)
              + "&meta=recordings+releasegroups";

      HttpRequest request =
          HttpRequest.newBuilder()
              .uri(URI.create(API_URL))
              .timeout(TIMEOUT)
              .header("Content-Type", "application/x-www-form-urlencoded")
              .header("User-Agent", "Yay-Tsa-Media-Server/0.1.0")
              .POST(HttpRequest.BodyPublishers.ofString(body))
              .build();

      log.info("AcoustID lookup: duration={}s, fingerprint={}chars", duration, fingerprint.length());

      HttpResponse<String> response =
          httpClient.send(request, HttpResponse.BodyHandlers.ofString());

      if (response.statusCode() != 200) {
        log.warn("AcoustID API returned status {}: {}", response.statusCode(), response.body());
        return Optional.empty();
      }

      AcoustIdResponse acoustIdResponse =
          objectMapper.readValue(response.body(), AcoustIdResponse.class);

      if (acoustIdResponse == null || !"ok".equals(acoustIdResponse.status)) {
        log.warn("AcoustID API error: {}", response.body());
        return Optional.empty();
      }

      if (acoustIdResponse.results == null || acoustIdResponse.results.isEmpty()) {
        log.info("AcoustID: no results found");
        return Optional.empty();
      }

      return selectBestResult(acoustIdResponse.results);

    } catch (Exception e) {
      log.warn("AcoustID lookup failed: {}", e.getMessage());
      return Optional.empty();
    }
  }

  private Optional<AcoustIdResult> selectBestResult(List<AcoustIdLookupResult> results) {
    for (AcoustIdLookupResult result : results) {
      if (result.score == null || result.score < 0.5) {
        continue;
      }

      if (result.recordings == null || result.recordings.isEmpty()) {
        continue;
      }

      for (AcoustIdRecording recording : result.recordings) {
        String artist = extractArtist(recording);
        String title = recording.title;

        if (artist == null || artist.isBlank() || title == null || title.isBlank()) {
          continue;
        }

        String album = null;
        Integer year = null;

        if (recording.releasegroups != null && !recording.releasegroups.isEmpty()) {
          AcoustIdReleaseGroup rg = recording.releasegroups.get(0);
          album = rg.title;
          if (rg.type != null && rg.type.equals("Single")) {
            album = null;
          }
        }

        if (recording.releasegroups != null) {
          for (AcoustIdReleaseGroup rg : recording.releasegroups) {
            if (rg.releases != null) {
              for (AcoustIdRelease release : rg.releases) {
                if (release.date != null && release.date.year != null) {
                  year = release.date.year;
                  break;
                }
              }
            }
            if (year != null) break;
          }
        }

        log.info(
            "AcoustID match: {} - {} (album: {}, year: {}, score: {})",
            artist,
            title,
            album,
            year,
            result.score);

        return Optional.of(
            new AcoustIdResult(artist, title, album, year, recording.id));
      }
    }

    log.info("AcoustID: no usable recordings in results");
    return Optional.empty();
  }

  private String extractArtist(AcoustIdRecording recording) {
    if (recording.artists != null && !recording.artists.isEmpty()) {
      StringBuilder sb = new StringBuilder();
      for (int i = 0; i < recording.artists.size(); i++) {
        if (i > 0) {
          String joinPhrase = recording.artists.get(i).joinphrase;
          sb.append(joinPhrase != null ? joinPhrase : ", ");
        }
        sb.append(recording.artists.get(i).name);
      }
      return sb.toString();
    }
    return null;
  }

  private String getApiKey() {
    return settingsService.get("metadata.acoustid.api-key", "ACOUSTID_API_KEY");
  }

  // AcoustID API response DTOs
  @JsonIgnoreProperties(ignoreUnknown = true)
  record AcoustIdResponse(String status, List<AcoustIdLookupResult> results) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  record AcoustIdLookupResult(
      String id, Double score, List<AcoustIdRecording> recordings) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  record AcoustIdRecording(
      String id,
      String title,
      List<AcoustIdArtist> artists,
      List<AcoustIdReleaseGroup> releasegroups) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  record AcoustIdArtist(String id, String name, String joinphrase) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  record AcoustIdReleaseGroup(
      String id,
      String title,
      String type,
      @JsonProperty("secondarytypes") List<String> secondaryTypes,
      List<AcoustIdRelease> releases) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  record AcoustIdRelease(
      String id, AcoustIdReleaseDate date) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  record AcoustIdReleaseDate(Integer year, Integer month, Integer day) {}
}
