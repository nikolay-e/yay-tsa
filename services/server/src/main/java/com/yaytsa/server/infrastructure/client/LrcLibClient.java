package com.yaytsa.server.infrastructure.client;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

/**
 * Client for the LRCLIB public API (https://lrclib.net).
 * Free, no API key required, community-sourced synced/plain lyrics.
 */
@Component
public class LrcLibClient {

  private static final Logger log = LoggerFactory.getLogger(LrcLibClient.class);
  private static final String BASE_URL = "https://lrclib.net/api";

  private final RestClient restClient;

  public record LrcLibResult(boolean found, String syncedLyrics, String plainLyrics) {}

  public LrcLibClient(RestClient.Builder restClientBuilder) {
    var factory = new SimpleClientHttpRequestFactory();
    factory.setConnectTimeout(Duration.ofSeconds(10));
    factory.setReadTimeout(Duration.ofSeconds(15));
    this.restClient = restClientBuilder.requestFactory(factory).build();
  }

  /**
   * Fetch lyrics with two-step strategy:
   * 1. Exact match by artist + title + album + duration
   * 2. Fallback: search by artist + title (no album/duration filter)
   */
  public LrcLibResult fetchLyrics(String artist, String title, String album, Long durationMs) {
    // Step 1: exact match
    LrcLibResult exact = fetchExact(artist, title, album, durationMs);
    if (exact.found()) {
      return exact;
    }

    // Step 2: broader exact match without album/duration
    if ((album != null && !album.isBlank()) || durationMs != null) {
      LrcLibResult broad = fetchExact(artist, title, null, null);
      if (broad.found()) {
        log.debug("LRCLIB: found lyrics via broad match for '{}' by '{}'", title, artist);
        return broad;
      }
    }

    // Step 3: search endpoint
    return search(artist, title);
  }

  private LrcLibResult fetchExact(String artist, String title, String album, Long durationMs) {
    try {
      StringBuilder url = new StringBuilder(BASE_URL + "/get?");
      url.append("artist_name=").append(encode(artist));
      url.append("&track_name=").append(encode(title));
      if (album != null && !album.isBlank()) {
        url.append("&album_name=").append(encode(album));
      }
      if (durationMs != null) {
        url.append("&duration=").append(durationMs / 1000);
      }

      @SuppressWarnings("unchecked")
      Map<String, Object> response =
          restClient
              .get()
              .uri(URI.create(url.toString()))
              .retrieve()
              .body(Map.class);

      return parseResult(response);

    } catch (HttpClientErrorException.NotFound e) {
      return new LrcLibResult(false, null, null);
    } catch (Exception e) {
      log.warn("LRCLIB exact fetch failed for '{}' by '{}': {}", title, artist, e.getMessage());
      return new LrcLibResult(false, null, null);
    }
  }

  private LrcLibResult search(String artist, String title) {
    try {
      String url =
          BASE_URL
              + "/search?artist_name="
              + encode(artist)
              + "&track_name="
              + encode(title);

      List<Map<String, Object>> results =
          restClient
              .get()
              .uri(URI.create(url))
              .retrieve()
              .body(new ParameterizedTypeReference<>() {});

      if (results == null || results.isEmpty()) {
        return new LrcLibResult(false, null, null);
      }

      // Pick first result that has lyrics
      for (Map<String, Object> item : results) {
        Boolean instrumental = item.get("instrumental") instanceof Boolean b ? b : false;
        if (Boolean.TRUE.equals(instrumental)) continue;

        LrcLibResult r = parseResult(item);
        if (r.found()) {
          log.debug("LRCLIB: found lyrics via search for '{}' by '{}'", title, artist);
          return r;
        }
      }

      return new LrcLibResult(false, null, null);

    } catch (Exception e) {
      log.warn("LRCLIB search failed for '{}' by '{}': {}", title, artist, e.getMessage());
      return new LrcLibResult(false, null, null);
    }
  }

  private LrcLibResult parseResult(Map<String, Object> response) {
    if (response == null) {
      return new LrcLibResult(false, null, null);
    }
    Boolean instrumental = response.get("instrumental") instanceof Boolean b ? b : false;
    if (Boolean.TRUE.equals(instrumental)) {
      return new LrcLibResult(false, null, null);
    }
    String synced = response.get("syncedLyrics") instanceof String s && !s.isBlank() ? s : null;
    String plain = response.get("plainLyrics") instanceof String p && !p.isBlank() ? p : null;
    boolean found = synced != null || plain != null;
    return new LrcLibResult(found, synced, plain);
  }

  private String encode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }
}
