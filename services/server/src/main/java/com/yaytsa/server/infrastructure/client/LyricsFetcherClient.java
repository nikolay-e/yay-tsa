package com.yaytsa.server.infrastructure.client;

import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

@Component
public class LyricsFetcherClient {

  private static final Logger log = LoggerFactory.getLogger(LyricsFetcherClient.class);

  private static final int CONNECT_TIMEOUT_SECONDS = 10;
  private static final int MAX_RETRIES = 2;
  private static final long INITIAL_RETRY_DELAY_MS = 1000;

  private final RestClient restClient;
  private final RestClient healthCheckClient;

  public record LyricsResult(boolean success, String outputPath, String source) {}

  public LyricsFetcherClient(
      RestClient.Builder restClientBuilder,
      @Value("${yaytsa.media.lyrics.fetcher-url:http://audio-separator:8000}") String fetcherUrl,
      @Value("${yaytsa.media.lyrics.timeout-seconds:20}") int timeoutSeconds) {

    var requestFactory = new SimpleClientHttpRequestFactory();
    requestFactory.setConnectTimeout(Duration.ofSeconds(CONNECT_TIMEOUT_SECONDS));
    requestFactory.setReadTimeout(Duration.ofSeconds(timeoutSeconds));

    this.restClient = restClientBuilder.baseUrl(fetcherUrl).requestFactory(requestFactory).build();

    var healthRequestFactory = new SimpleClientHttpRequestFactory();
    healthRequestFactory.setConnectTimeout(Duration.ofSeconds(5));
    healthRequestFactory.setReadTimeout(Duration.ofSeconds(5));

    this.healthCheckClient =
        restClientBuilder.baseUrl(fetcherUrl).requestFactory(healthRequestFactory).build();
  }

  public LyricsResult fetchLyrics(String artist, String title, String outputPath) {
    var request = Map.of("artist", artist, "title", title, "outputPath", outputPath);

    Exception lastException = null;

    for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
      try {
        @SuppressWarnings("unchecked")
        Map<String, Object> response =
            restClient
                .post()
                .uri("/api/fetch-lyrics")
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .body(Map.class);

        if (response == null) {
          return new LyricsResult(false, null, null);
        }

        boolean success = Boolean.TRUE.equals(response.get("success"));
        String path =
            response.get("outputPath") != null ? response.get("outputPath").toString() : null;
        String source = response.get("source") != null ? response.get("source").toString() : null;

        return new LyricsResult(success, path, source);

      } catch (ResourceAccessException e) {
        lastException = e;
        if (isRetryableException(e) && attempt < MAX_RETRIES) {
          long delay = INITIAL_RETRY_DELAY_MS * (1L << (attempt - 1));
          log.warn(
              "Lyrics fetch attempt {} failed for '{}' (retrying in {}ms): {}",
              attempt,
              title,
              delay,
              e.getMessage());
          try {
            Thread.sleep(delay);
          } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Lyrics fetch interrupted", ie);
          }
        }
      } catch (Exception e) {
        log.debug("Lyrics fetch failed for '{}': {}", title, e.getMessage());
        return new LyricsResult(false, null, null);
      }
    }

    log.debug("Lyrics fetch failed for '{}' after {} attempts", title, MAX_RETRIES);
    return new LyricsResult(false, null, null);
  }

  private boolean isRetryableException(ResourceAccessException e) {
    Throwable cause = e.getCause();
    return cause instanceof SocketTimeoutException
        || cause instanceof java.net.ConnectException
        || cause instanceof java.net.NoRouteToHostException
        || cause instanceof java.net.UnknownHostException;
  }

  public boolean isHealthy() {
    try {
      healthCheckClient.get().uri("/health").retrieve().body(String.class);
      return true;
    } catch (Exception e) {
      log.warn("Lyrics fetcher health check failed: {}", e.getMessage());
      return false;
    }
  }
}
