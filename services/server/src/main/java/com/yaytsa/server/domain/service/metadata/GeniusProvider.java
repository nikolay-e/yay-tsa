package com.yaytsa.server.domain.service.metadata;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;

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
  private final String accessToken;
  private final ObjectMapper objectMapper;

  public GeniusProvider(
      @Value("${yaytsa.metadata.genius.access-token:}") String accessToken,
      ObjectMapper objectMapper) {
    this.httpClient = HttpClient.newBuilder()
        .connectTimeout(TIMEOUT)
        .build();
    this.accessToken = accessToken;
    this.objectMapper = objectMapper;
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

      log.info("Genius API request: {}", searchUrl);
      log.info("Genius token present: {}, length: {}", accessToken != null && !accessToken.isBlank(), accessToken != null ? accessToken.length() : 0);

      // Use native Java HttpClient instead of RestTemplate
      HttpRequest request = HttpRequest.newBuilder()
          .uri(URI.create(searchUrl))
          .timeout(TIMEOUT)
          .header("Authorization", "Bearer " + accessToken)
          .header("Accept", "application/json")
          .header("User-Agent", "Yay-Tsa-Media-Server/0.1.0")
          .GET()
          .build();

      HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

      log.info("Genius API response status: {}", response.statusCode());
      log.info("Genius API raw JSON (first 500 chars): {}",
          response.body() != null && response.body().length() > 500
              ? response.body().substring(0, 500) : response.body());

      // Parse JSON manually
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

          // Get detailed song info for album data
          if (song.id != null) {
            try {
              String songUrl = API_BASE + "/songs/" + song.id;
              log.debug("Fetching detailed song info from: {}", songUrl);

              HttpRequest detailRequest = HttpRequest.newBuilder()
                  .uri(URI.create(songUrl))
                  .timeout(TIMEOUT)
                  .header("Authorization", "Bearer " + accessToken)
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
                  year = extractYear(detail.releaseDate);
                }
              } else {
                log.warn("Detailed song response is null or empty");
              }
            } catch (Exception e) {
              log.warn("Failed to fetch detailed song info from Genius: {}", e.getMessage(), e);
            }
          }

          String artistName =
              song.primaryArtist != null ? song.primaryArtist.name : artist;

          // Prefer song_art_image_url, fallback to header_image_url
          String coverArtUrl = song.songArtImageUrl != null
              ? song.songArtImageUrl
              : song.headerImageUrl;

          // Get artist image URL
          String artistImageUrl = song.primaryArtist != null ? song.primaryArtist.imageUrl : null;

          log.info(
              "Genius match: {} - {} → {} ({}) [confidence: {:.2f}], cover: {}, artist image: {}",
              artist,
              title,
              albumName,
              year,
              confidence,
              coverArtUrl != null ? "available" : "none",
              artistImageUrl != null ? "available" : "none");

          // Fetch lyrics from song page
          String lyrics = null;
          if (song.url != null) {
            try {
              lyrics = fetchLyrics(song.url);
              if (lyrics != null) {
                log.info("Fetched lyrics for: {} - {} ({} chars)", artist, title, lyrics.length());
              }
            } catch (Exception e) {
              log.warn("Failed to fetch lyrics from {}: {}", song.url, e.getMessage());
            }
          }

          return Optional.of(
              new EnrichedMetadata(
                  artistName, cleanAlbumName(albumName), year, null, coverArtUrl, artistImageUrl, lyrics, confidence, getProviderName()));
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
    return accessToken != null && !accessToken.isBlank();
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

  private String fetchLyrics(String songUrl) {
    try {
      log.debug("Fetching lyrics from: {}", songUrl);

      HttpRequest request = HttpRequest.newBuilder()
          .uri(URI.create(songUrl))
          .timeout(TIMEOUT)
          .header("User-Agent", "Yay-Tsa-Media-Server/0.1.0")
          .header("Accept", "text/html,application/xhtml+xml")
          .GET()
          .build();

      HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

      if (response.statusCode() != 200) {
        log.warn("Failed to fetch lyrics page, status: {}", response.statusCode());
        return null;
      }

      Document doc = Jsoup.parse(response.body());

      // Genius stores lyrics in div elements with data-lyrics-container="true"
      Elements lyricsContainers = doc.select("div[data-lyrics-container=true]");

      if (lyricsContainers.isEmpty()) {
        log.warn("No lyrics containers found on page: {}", songUrl);
        return null;
      }

      StringBuilder lyrics = new StringBuilder();
      for (Element container : lyricsContainers) {
        // Extract text, preserving line breaks
        String html = container.html();

        // Check for instrumental marker
        if (html.toLowerCase().contains("[instrumental]") ||
            html.toLowerCase().contains("(instrumental)")) {
          log.info("Track marked as instrumental on Genius");
          return null;
        }

        String text = html
            .replaceAll("<br\\s*/?>", "\n")
            .replaceAll("</(?:div|p)>", "\n")
            .replaceAll("<[^>]+>", "")
            .replaceAll("&amp;", "&")
            .replaceAll("&lt;", "<")
            .replaceAll("&gt;", ">")
            .replaceAll("&quot;", "\"")
            .replaceAll("&#x27;", "'")
            .replaceAll("&#39;", "'")
            .replaceAll("&nbsp;", " ")
            .trim();

        if (!text.isEmpty()) {
          lyrics.append(text);
          lyrics.append("\n\n");
        }
      }

      String result = lyrics.toString().trim();

      // Final validation
      if (result.isEmpty() || result.length() < 20) {
        log.debug("Lyrics too short after extraction: {} chars", result.length());
        return null;
      }

      return result;

    } catch (Exception e) {
      log.warn("Error fetching lyrics from {}: {}", songUrl, e.getMessage());
      return null;
    }
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
  record GeniusAlbum(String name) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  record GeniusSongResponse(GeniusSongResponseData response) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  record GeniusSongResponseData(GeniusSongDetail song) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  record GeniusSongDetail(
      GeniusAlbum album,
      @JsonProperty("release_date") String releaseDate) {}
}
