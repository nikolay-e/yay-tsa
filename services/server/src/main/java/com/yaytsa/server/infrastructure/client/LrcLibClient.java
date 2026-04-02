package com.yaytsa.server.infrastructure.client;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Component
public class LrcLibClient {

  private static final Logger log = LoggerFactory.getLogger(LrcLibClient.class);
  private static final String BASE_URL = "https://lrclib.net/api";

  private final RestClient restClient;

  public record LrcLibResult(boolean found, String syncedLyrics, String plainLyrics) {}

  public LrcLibClient(RestClient.Builder restClientBuilder) {
    var httpClient =
        java.net.http.HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    var factory = new JdkClientHttpRequestFactory(httpClient);
    factory.setReadTimeout(Duration.ofSeconds(15));
    this.restClient =
        restClientBuilder
            .baseUrl(BASE_URL)
            .requestFactory(factory)
            .defaultHeader(HttpHeaders.USER_AGENT, "yaytsa/1.0 (https://yay-tsa.com)")
            .build();
  }

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
    LrcLibResult searchResult = search(artist, title);
    if (searchResult.found()) {
      return searchResult;
    }

    // Step 4: if artist name has parenthetical (e.g. "Макс Корж (Max Korzh)"), strip it and retry
    String cleanedArtist = artist.replaceAll("\\s*\\([^)]*\\)\\s*", "").trim();
    if (!cleanedArtist.equals(artist) && !cleanedArtist.isBlank()) {
      log.debug(
          "LRCLIB: retrying search with cleaned artist '{}' (was '{}')", cleanedArtist, artist);
      LrcLibResult cleaned = search(cleanedArtist, title);
      if (cleaned.found()) {
        return cleaned;
      }
    }

    return searchResult;
  }

  private LrcLibResult fetchExact(String artist, String title, String album, Long durationMs) {
    try {
      @SuppressWarnings("unchecked")
      Map<String, Object> response =
          restClient
              .get()
              .uri(
                  uriBuilder -> {
                    uriBuilder
                        .path("/get")
                        .queryParam("artist_name", artist)
                        .queryParam("track_name", title);
                    if (album != null && !album.isBlank()) {
                      uriBuilder.queryParam("album_name", album);
                    }
                    if (durationMs != null) {
                      uriBuilder.queryParam("duration", durationMs / 1000);
                    }
                    return uriBuilder.build();
                  })
              .retrieve()
              .body(Map.class);

      return parseResult(response);

    } catch (HttpClientErrorException.NotFound e) {
      return new LrcLibResult(false, null, null);
    } catch (RestClientException e) {
      log.warn("LRCLIB exact fetch failed for '{}' by '{}': {}", title, artist, e.getMessage());
      return new LrcLibResult(false, null, null);
    }
  }

  private LrcLibResult search(String artist, String title) {
    try {
      List<Map<String, Object>> results =
          restClient
              .get()
              .uri(
                  uriBuilder ->
                      uriBuilder
                          .path("/search")
                          .queryParam("artist_name", artist)
                          .queryParam("track_name", title)
                          .build())
              .retrieve()
              .body(new ParameterizedTypeReference<>() {});

      if (results == null || results.isEmpty()) {
        return new LrcLibResult(false, null, null);
      }

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

    } catch (RestClientException e) {
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
}
