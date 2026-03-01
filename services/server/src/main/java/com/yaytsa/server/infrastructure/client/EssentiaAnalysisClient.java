package com.yaytsa.server.infrastructure.client;

import com.yaytsa.server.domain.service.AppSettingsService;
import java.net.SocketTimeoutException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

@Component
public class EssentiaAnalysisClient {

  private static final Logger log = LoggerFactory.getLogger(EssentiaAnalysisClient.class);

  private static final int CONNECT_TIMEOUT_SECONDS = 10;
  private static final int READ_TIMEOUT_MINUTES = 5;
  private static final int HEALTH_CHECK_TIMEOUT_SECONDS = 5;
  private static final int MAX_RETRIES = 3;
  private static final long INITIAL_RETRY_DELAY_MS = 1000;

  private final RestClient restClient;
  private final RestClient healthCheckClient;
  private final AppSettingsService settingsService;
  private final String defaultUrl;

  public EssentiaAnalysisClient(
      RestClient.Builder restClientBuilder,
      AppSettingsService settingsService,
      @Value("${yaytsa.media.radio.analyzer-url:http://essentia-analyzer:8001}")
          String analyzerUrl) {
    this.settingsService = settingsService;
    this.defaultUrl = analyzerUrl;

    var analysisRequestFactory = new SimpleClientHttpRequestFactory();
    analysisRequestFactory.setConnectTimeout(Duration.ofSeconds(CONNECT_TIMEOUT_SECONDS));
    analysisRequestFactory.setReadTimeout(Duration.ofMinutes(READ_TIMEOUT_MINUTES));
    this.restClient = restClientBuilder.requestFactory(analysisRequestFactory).build();

    var healthRequestFactory = new SimpleClientHttpRequestFactory();
    healthRequestFactory.setConnectTimeout(Duration.ofSeconds(HEALTH_CHECK_TIMEOUT_SECONDS));
    healthRequestFactory.setReadTimeout(Duration.ofSeconds(HEALTH_CHECK_TIMEOUT_SECONDS));
    this.healthCheckClient = restClientBuilder.requestFactory(healthRequestFactory).build();
  }

  @Cacheable(value = "essentia-analyzer-url", key = "'base-url'")
  public String getBaseUrl() {
    String configured = settingsService.get("service.essentia-url", "ESSENTIA_ANALYZER_URL");
    return configured.isBlank() ? defaultUrl : configured;
  }

  public record AnalysisResult(
      String mood, int energy, int valence, int danceability, int arousal) {}

  public Optional<AnalysisResult> analyze(Path audioFilePath) {
    log.info("Requesting Essentia analysis for {}", audioFilePath);

    var request = Map.of("audioPath", audioFilePath.toString());
    Exception lastException = null;

    for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
      try {
        @SuppressWarnings("unchecked")
        Map<String, Object> response =
            restClient
                .post()
                .uri(getBaseUrl() + "/api/analyze")
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .body(Map.class);

        if (response == null || !response.containsKey("mood")) {
          log.warn("Invalid response from essentia-analyzer for {}", audioFilePath);
          return Optional.empty();
        }

        return Optional.of(
            new AnalysisResult(
                (String) response.get("mood"),
                toInt(response.get("energy")),
                toInt(response.get("valence")),
                toInt(response.get("danceability")),
                toInt(response.get("arousal"))));

      } catch (ResourceAccessException e) {
        lastException = e;
        if (isRetryable(e) && attempt < MAX_RETRIES) {
          long delay = INITIAL_RETRY_DELAY_MS * (1L << (attempt - 1));
          log.warn(
              "Essentia analysis attempt {} failed (retrying in {}ms): {}",
              attempt, delay, e.getMessage());
          try {
            Thread.sleep(delay);
          } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return Optional.empty();
          }
        } else {
          log.error("Essentia analysis failed after {} attempts: {}", attempt, e.getMessage());
          return Optional.empty();
        }
      } catch (Exception e) {
        log.error("Essentia analysis failed for {}: {}", audioFilePath, e.getMessage());
        return Optional.empty();
      }
    }

    log.error("Essentia analysis exhausted retries: {}", lastException != null ? lastException.getMessage() : "");
    return Optional.empty();
  }

  public boolean isHealthy() {
    try {
      healthCheckClient.get().uri(getBaseUrl() + "/health").retrieve().body(String.class);
      return true;
    } catch (Exception e) {
      log.warn("Essentia analyzer health check failed: {}", e.getMessage());
      return false;
    }
  }

  private boolean isRetryable(ResourceAccessException e) {
    Throwable cause = e.getCause();
    return cause instanceof SocketTimeoutException
        || cause instanceof java.net.ConnectException
        || cause instanceof java.net.NoRouteToHostException
        || cause instanceof java.net.UnknownHostException;
  }

  private int toInt(Object value) {
    if (value instanceof Number n) return n.intValue();
    return 5;
  }
}
