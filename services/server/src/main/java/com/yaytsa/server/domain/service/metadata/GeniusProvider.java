package com.yaytsa.server.domain.service.metadata;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yaytsa.server.domain.service.AppSettingsService;
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
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;

/**
 * Genius metadata provider.
 *
 * <p>Queries Genius API for track information. Genius has excellent coverage for modern music,
 * especially hip-hop, pop, and Russian artists. Also provides lyrics.
 *
 * <p>Free API access: https://genius.com/api-clients
 */
@Component
public class GeniusProvider implements MetadataProvider {

  private static final Logger log = LoggerFactory.getLogger(GeniusProvider.class);

  private static final String API_BASE = "https://api.genius.com";
  private static final Duration TIMEOUT = Duration.ofSeconds(10);

  private final HttpClient httpClient;
  private final AppSettingsService settingsService;
  private final ObjectMapper objectMapper;

  public GeniusProvider(AppSettingsService settingsService, ObjectMapper objectMapper) {
    this.httpClient = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
    this.settingsService = settingsService;
    this.objectMapper = objectMapper;
  }

  private String getAccessToken() {
    return settingsService.get("metadata.genius.token", "GENIUS_ACCESS_TOKEN");
  }

  @Override
  @Cacheable(value = "metadata-genius", key = "#artist + ':' + #title")
  public Optional<EnrichedMetadata> findMetadata(String artist, String title) {
    if (!isEnabled()) {
      return Optional.empty();
    }

    try {
      log.debug("Querying Genius for: {} - {}", artist, title);

      // Search for song
      String searchQuery = String.format("%s %s", artist, title);
      // URLEncoder uses + for spaces, but Genius API wants %20
      String encodedQuery = URLEncoder.encode(searchQuery, StandardCharsets.UTF_8).replace("+", "%20");
      String searchUrl = API_BASE + "/search?q=" + encodedQuery;

      log.debug("Genius API request: {}", searchUrl);

      // Use native Java HttpClient instead of RestTemplate
      HttpRequest request = HttpRequest.newBuilder()
          .uri(URI.create(searchUrl))
          .timeout(TIMEOUT)
          .header("Authorization", "Bearer " + getAccessToken())
          .header("Accept", "application/json")
          .header("User-Agent", "Yay-Tsa-Media-Server/0.1.0")
          .GET()
          .build();

      HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

      log.debug("Genius API response status: {}", response.statusCode());

      // Parse JSON
      GeniusSearchResponse searchResponse = objectMapper.readValue(response.body(), GeniusSearchResponse.class);

      if (searchResponse == null) {
        log.warn("Genius search response is null for: {} - {}", artist, title);
        return Optional.empty();
      }
      if (searchResponse.response == null) {
        log.warn("Genius search response.response is null for: {} - {}", artist, title);
        return Optional.empty();
      }
      if (searchResponse.response.hits == null) {
        log.warn("Genius search response.hits is null for: {} - {}", artist, title);
        return Optional.empty();
      }
      log.info("Genius returned {} hits for: {} - {}",
          searchResponse.response.hits.size(), artist, title);
      if (searchResponse.response.hits.isEmpty()) {
        log.info("No results from Genius for: {} - {}", artist, title);
        return Optional.empty();
      }

      // Find best match from search results
      for (GeniusHit hit : searchResponse.response.hits) {
        if (hit.result == null) continue;

        GeniusSong song = hit.result;

        // Calculate confidence
        double confidence = calculateConfidence(artist, title, song);

        if (confidence >= 0.70) {
          String albumName = null;
          Integer year = null;
          Integer totalTracks = null;
          Long albumId = null;

          // Get detailed song info for album data
          if (song.id != null) {
            try {
              String songUrl = API_BASE + "/songs/" + song.id;
              log.debug("Fetching detailed song info from: {}", songUrl);

              HttpRequest detailRequest = HttpRequest.newBuilder()
                  .uri(URI.create(songUrl))
                  .timeout(TIMEOUT)
                  .header("Authorization", "Bearer " + getAccessToken())
                  .header("Accept", "application/json")
                  .header("User-Agent", "Yay-Tsa-Media-Server/0.1.0")
                  .GET()
                  .build();

              HttpResponse<String> detailResponse = httpClient.send(detailRequest, HttpResponse.BodyHandlers.ofString());
              GeniusSongResponse songResponse = objectMapper.readValue(detailResponse.body(), GeniusSongResponse.class);

              if (songResponse != null && songResponse.response != null) {
                GeniusSongDetail detail = songResponse.response.song;
                log.debug("Got detailed song response, album: {}", detail.album != null ? detail.album.name : "null");
                if (detail.album != null) {
                  albumName = detail.album.name;
                  albumId = detail.album.id;
                  year = extractYear(detail.releaseDate);
                }
              } else {
                log.warn("Detailed song response is null or empty");
              }
            } catch (Exception e) {
              log.warn("Failed to fetch detailed song info from Genius: {}", e.getMessage(), e);
            }
          }

          // Fetch total track count for the album
          if (albumId != null) {
            try {
              String albumTracksUrl = API_BASE + "/albums/" + albumId + "/tracks";
              HttpRequest albumTracksRequest = HttpRequest.newBuilder()
                  .uri(URI.create(albumTracksUrl))
                  .timeout(TIMEOUT)
                  .header("Authorization", "Bearer " + getAccessToken())
                  .header("Accept", "application/json")
                  .header("User-Agent", "Yay-Tsa-Media-Server/0.1.0")
                  .GET()
                  .build();
              HttpResponse<String> albumTracksResponse = httpClient.send(albumTracksRequest, HttpResponse.BodyHandlers.ofString());
              GeniusAlbumTracksResponse albumTracks = objectMapper.readValue(albumTracksResponse.body(), GeniusAlbumTracksResponse.class);
              if (albumTracks != null && albumTracks.response != null && albumTracks.response.tracks != null) {
                totalTracks = albumTracks.response.tracks.size();
                log.debug("Genius album {} has {} tracks", albumName, totalTracks);
              }
            } catch (Exception e) {
              log.warn("Failed to fetch album tracks from Genius: {}", e.getMessage());
            }
          }

          String artistName =
              song.primaryArtist != null ? song.primaryArtist.name : artist;

          // Use song art image from Genius (cached behind auth — same model as Plex/Navidrome)
          String coverArtUrl = song.songArtImageUrl != null ? song.songArtImageUrl
              : song.headerImageUrl;
          String artistImageUrl = song.primaryArtist != null ? song.primaryArtist.imageUrl : null;

          log.debug(
              "Genius match: {} - {} -> {} ({}) totalTracks={} coverArt={}",
              artist, title, albumName, year, totalTracks, coverArtUrl != null ? "yes" : "no");

          return Optional.of(
              new EnrichedMetadata(
                  artistName, cleanAlbumName(albumName), year, null, coverArtUrl, artistImageUrl, null, totalTracks, confidence, getProviderName()));
        }
      }

      log.debug("No high-confidence match from Genius");
      return Optional.empty();

    } catch (Exception e) {
      log.warn("Genius query failed: {}", e.getMessage());
      return Optional.empty();
    }
  }

  @Override
  public String getProviderName() {
    return "Genius";
  }

  @Override
  public boolean isEnabled() {
    return !getAccessToken().isBlank();
  }

  @Override
  public int getPriority() {
    return 75; // High priority (excellent for modern music, Russian artists)
  }

  private double calculateConfidence(String originalArtist, String originalTitle, GeniusSong song) {
    double artistScore = 0.0;
    double titleScore = 0.0;

    // Artist matching
    if (song.primaryArtist != null && song.primaryArtist.name != null) {
      String geniusArtist = song.primaryArtist.name.toLowerCase();
      String searchArtist = originalArtist.toLowerCase();

      if (geniusArtist.equals(searchArtist)) {
        artistScore = 1.0;
      } else if (geniusArtist.contains(searchArtist) || searchArtist.contains(geniusArtist)) {
        artistScore = 0.85;
      } else {
        artistScore = 0.5;
      }
    }

    // Title matching
    if (song.title != null) {
      String geniusTitle = normalizeTitle(song.title);
      String searchTitle = normalizeTitle(originalTitle);

      if (geniusTitle.equals(searchTitle)) {
        titleScore = 1.0;
      } else if (geniusTitle.contains(searchTitle) || searchTitle.contains(geniusTitle)) {
        titleScore = 0.85;
      } else {
        titleScore = 0.5;
      }
    }

    // Boost for verification status
    double verificationBoost = 0.0;
    if (song.verifiedAnnotationsCount != null && song.verifiedAnnotationsCount > 0) {
      verificationBoost = 0.05;
    }

    return Math.min(1.0, (artistScore * 0.4) + (titleScore * 0.6) + verificationBoost);
  }

  private String normalizeTitle(String title) {
    // Remove common annotations that hurt search accuracy
    String cleaned = title
        .replaceAll("(?i)\\s*\\(feat\\.?\\s+[^)]*\\)", "") // (feat. Artist)
        .replaceAll("(?i)\\s*\\[feat\\.?\\s+[^\\]]*\\]", "") // [feat. Artist]
        .replaceAll("(?i)\\s*\\(with\\s+[^)]*\\)", "") // (with Artist)
        .replaceAll("(?i)\\s*\\(remix[^)]*\\)", "") // (Remix)
        .replaceAll("(?i)\\s*\\[remix[^\\]]*\\]", "") // [Remix]
        .replaceAll("(?i)\\s*\\(remaster(ed)?[^)]*\\)", "") // (Remastered)
        .replaceAll("(?i)\\s*\\(live[^)]*\\)", "") // (Live)
        .replaceAll("(?i)\\s*\\(acoustic[^)]*\\)", "") // (Acoustic)
        .replaceAll("(?i)\\s*\\(radio\\s*edit\\)", "") // (Radio Edit)
        .replaceAll("(?i)\\s*\\(explicit\\)", "") // (Explicit)
        .replaceAll("(?i)\\s*\\(clean\\)", "") // (Clean)
        .toLowerCase()
        .replaceAll("\\([^)]*\\)", "") // Remove remaining parentheses
        .replaceAll("\\[[^\\]]*\\]", "") // Remove brackets
        .replaceAll("[\\-_]", " ")
        .replaceAll("\\s+", " ")
        .trim();

    return cleaned.isEmpty() ? title.toLowerCase().trim() : cleaned;
  }

  private String cleanAlbumName(String albumName) {
    if (albumName == null) {
      return null;
    }
    // Remove English translations in parentheses like "(jump, fool!)"
    return albumName
        .replaceAll("\\s*\\([^)]*\\)\\s*", " ")
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
  record GeniusSearchResponse(GeniusResponse response) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  record GeniusResponse(List<GeniusHit> hits) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  record GeniusHit(GeniusSong result) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  record GeniusSong(
      Long id,
      String title,
      String url,
      @JsonProperty("primary_artist") GeniusArtist primaryArtist,
      GeniusAlbum album,
      @JsonProperty("song_art_image_url") String songArtImageUrl,
      @JsonProperty("header_image_url") String headerImageUrl,
      @JsonProperty("verified_annotations_count") Integer verifiedAnnotationsCount) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  record GeniusArtist(
      Long id,
      String name,
      @JsonProperty("image_url") String imageUrl) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  record GeniusAlbum(Long id, String name) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  record GeniusSongResponse(GeniusSongResponseData response) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  record GeniusSongResponseData(GeniusSongDetail song) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  record GeniusSongDetail(
      GeniusAlbum album,
      @JsonProperty("release_date") String releaseDate) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  record GeniusAlbumTracksResponse(GeniusAlbumTracksData response) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  record GeniusAlbumTracksData(List<Object> tracks) {}
}
