package com.yaytsa.server.infrastructure.client;

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
import org.springframework.web.client.RestClient;

@Component
@Slf4j
public class EmbeddingExtractionClient {

  private static final int MAX_RETRIES = 2;
  private static final long INITIAL_RETRY_DELAY_MS = 2000;
  private static final int TEXT_MAX_CHARS = 300;
  private static final ParameterizedTypeReference<Map<String, Object>> MAP_TYPE =
      new ParameterizedTypeReference<>() {};

  private final RestClient restClient;
  private final RestClient healthCheckClient;

  public EmbeddingExtractionClient(
      RestClient.Builder restClientBuilder,
      @Value("${yaytsa.media.embedding-extraction.url:http://feature-extractor:8000}")
          String extractorUrl) {
    this.restClient =
        restClientBuilder.baseUrl(extractorUrl).requestFactory(requestFactory(10, 900)).build();
    this.healthCheckClient =
        restClientBuilder.baseUrl(extractorUrl).requestFactory(requestFactory(5, 5)).build();
  }

  public record EmbeddingResult(
      String trackId,
      List<? extends Number> embeddingClap,
      List<? extends Number> embeddingMert,
      int processingTimeMs) {}

  @SuppressWarnings("unchecked")
  public EmbeddingResult extract(UUID trackId, String filePath) {
    log.info("Requesting embedding extraction for track {} from {}", trackId, filePath);
    var request = Map.of("track_id", trackId.toString(), "file_path", filePath);
    Exception lastException = null;
    for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
      try {
        var response =
            restClient
                .post()
                .uri("/api/v1/embed")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .body(MAP_TYPE);
        if (response == null) throw new RuntimeException("Null response from embedding extractor");
        int time = response.get("processing_time_ms") instanceof Number n ? n.intValue() : 0;
        log.info("Embedding extraction completed for track {} in {}ms", trackId, time);
        return new EmbeddingResult(
            trackId.toString(),
            (List<? extends Number>) response.get("embedding_clap"),
            (List<? extends Number>) response.get("embedding_mert"),
            time);
      } catch (Exception e) {
        lastException = e;
        if (attempt < MAX_RETRIES) {
          long delay = INITIAL_RETRY_DELAY_MS * (1L << (attempt - 1));
          log.warn(
              "Embedding extraction attempt {} failed for track {} (retrying in {}ms): {}",
              attempt,
              trackId,
              delay,
              e.getMessage());
          try {
            Thread.sleep(delay);
          } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Embedding extraction interrupted", ie);
          }
        } else {
          throw new RuntimeException("Embedding extraction failed: " + e.getMessage(), e);
        }
      }
    }
    throw new RuntimeException(
        "Embedding extraction failed after " + MAX_RETRIES + " attempts", lastException);
  }

  @SuppressWarnings("unchecked")
  public List<Float> encodeText(String query) {
    if (query.length() > TEXT_MAX_CHARS) {
      log.warn("Text query truncated from {} to {} chars", query.length(), TEXT_MAX_CHARS);
      query = query.substring(0, TEXT_MAX_CHARS);
    }
    var request = Map.of("text", query);
    var response =
        restClient
            .post()
            .uri("/api/v1/embed/text")
            .contentType(MediaType.APPLICATION_JSON)
            .body(request)
            .retrieve()
            .body(MAP_TYPE);
    if (response == null) throw new RuntimeException("Null response from text embedding");
    var embedding = (List<Number>) response.get("embedding");
    return embedding.stream().map(Number::floatValue).toList();
  }

  public boolean isAvailable() {
    try {
      var response = healthCheckClient.get().uri("/api/v1/embed/status").retrieve().body(MAP_TYPE);
      return response != null && Boolean.TRUE.equals(response.get("available"));
    } catch (Exception e) {
      log.debug("Embedding extractor not available: {}", e.getMessage());
      return false;
    }
  }

  private static SimpleClientHttpRequestFactory requestFactory(int connectSec, int readSec) {
    var factory = new SimpleClientHttpRequestFactory();
    factory.setConnectTimeout(Duration.ofSeconds(connectSec));
    factory.setReadTimeout(Duration.ofSeconds(readSec));
    return factory;
  }
}
