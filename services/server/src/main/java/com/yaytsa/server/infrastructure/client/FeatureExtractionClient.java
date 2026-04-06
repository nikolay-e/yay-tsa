package com.yaytsa.server.infrastructure.client;

import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

@Component
@Slf4j
public class FeatureExtractionClient {
  private static final int MAX_RETRIES = 2;
  private static final long INITIAL_RETRY_DELAY_MS = 2000;
  private static final ParameterizedTypeReference<Map<String, Object>> MAP_TYPE =
      new ParameterizedTypeReference<>() {};

  private final RestClient restClient;
  private final RestClient healthCheckClient;

  public FeatureExtractionClient(
      RestClient.Builder restClientBuilder,
      @Value("${yaytsa.media.feature-extraction.url:http://feature-extractor:8000}")
          String extractorUrl) {
    this.restClient =
        restClientBuilder.baseUrl(extractorUrl).requestFactory(requestFactory(10, 120)).build();
    this.healthCheckClient =
        restClientBuilder.baseUrl(extractorUrl).requestFactory(requestFactory(5, 5)).build();
  }

  public record ExtractionResult(
      String trackId,
      Map<String, Object> features,
      List<? extends Number> embeddingDiscogs,
      List<? extends Number> embeddingMusicnn,
      int processingTimeMs) {}

  @SuppressWarnings("unchecked")
  public ExtractionResult extract(UUID trackId, String filePath) {
    log.info("Requesting feature extraction for track {} from {}", trackId, filePath);
    var request = Map.of("track_id", trackId.toString(), "file_path", filePath);
    Exception lastException = null;
    for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
      try {
        var response =
            restClient
                .post()
                .uri("/api/v1/extract")
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .body(MAP_TYPE);
        if (response == null) throw new RuntimeException("Null response from feature extractor");
        int time = response.get("processing_time_ms") instanceof Number n ? n.intValue() : 0;
        log.info("Feature extraction completed for track {} in {}ms", trackId, time);
        return new ExtractionResult(
            trackId.toString(),
            (Map<String, Object>) response.get("features"),
            (List<? extends Number>) response.get("embedding_discogs"),
            (List<? extends Number>) response.get("embedding_musicnn"),
            time);
      } catch (ResourceAccessException e) {
        lastException = e;
        if (isRetryable(e) && attempt < MAX_RETRIES) {
          long delay = INITIAL_RETRY_DELAY_MS * (1L << (attempt - 1));
          log.warn(
              "Feature extraction attempt {} failed for track {} (retrying in {}ms): {}",
              attempt,
              trackId,
              delay,
              e.getMessage());
          try {
            Thread.sleep(delay);
          } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Feature extraction interrupted", ie);
          }
        } else {
          throw new RuntimeException("Feature extraction failed: " + e.getMessage(), e);
        }
      }
    }
    throw new RuntimeException(
        "Feature extraction failed after " + MAX_RETRIES + " attempts", lastException);
  }

  public boolean isAvailable() {
    try {
      var response =
          healthCheckClient.get().uri("/api/v1/extract/status").retrieve().body(MAP_TYPE);
      return response != null && Boolean.TRUE.equals(response.get("available"));
    } catch (Exception e) {
      log.debug("Feature extractor not available: {}", e.getMessage());
      return false;
    }
  }

  public boolean isHealthy() {
    try {
      healthCheckClient.get().uri("/health").retrieve().body(String.class);
      return true;
    } catch (Exception e) {
      log.warn("Feature extractor health check failed: {}", e.getMessage());
      return false;
    }
  }

  private static SimpleClientHttpRequestFactory requestFactory(int connectSec, int readSec) {
    var factory = new SimpleClientHttpRequestFactory();
    factory.setConnectTimeout(Duration.ofSeconds(connectSec));
    factory.setReadTimeout(Duration.ofSeconds(readSec));
    return factory;
  }

  private static boolean isRetryable(ResourceAccessException e) {
    Throwable cause = e.getCause();
    return cause instanceof SocketTimeoutException
        || cause instanceof java.net.ConnectException
        || cause instanceof java.net.NoRouteToHostException
        || cause instanceof java.net.UnknownHostException;
  }
}
