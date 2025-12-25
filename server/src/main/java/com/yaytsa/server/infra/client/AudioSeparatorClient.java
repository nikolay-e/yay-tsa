package com.yaytsa.server.infra.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.net.SocketTimeoutException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

@Component
public class AudioSeparatorClient {

    private static final Logger log = LoggerFactory.getLogger(AudioSeparatorClient.class);

    private static final int CONNECT_TIMEOUT_SECONDS = 10;
    private static final int READ_TIMEOUT_MINUTES = 10;
    private static final int HEALTH_CHECK_TIMEOUT_SECONDS = 5;
    private static final int MAX_RETRIES = 3;
    private static final long INITIAL_RETRY_DELAY_MS = 1000;

    private final RestClient restClient;
    private final RestClient healthCheckClient;

    public AudioSeparatorClient(
            RestClient.Builder restClientBuilder,
            @Value("${yaytsa.media.karaoke.separator-url:http://audio-separator:8000}") String separatorUrl) {

        var separationRequestFactory = new SimpleClientHttpRequestFactory();
        separationRequestFactory.setConnectTimeout(Duration.ofSeconds(CONNECT_TIMEOUT_SECONDS));
        separationRequestFactory.setReadTimeout(Duration.ofMinutes(READ_TIMEOUT_MINUTES));

        this.restClient = restClientBuilder
                .baseUrl(separatorUrl)
                .requestFactory(separationRequestFactory)
                .build();

        var healthRequestFactory = new SimpleClientHttpRequestFactory();
        healthRequestFactory.setConnectTimeout(Duration.ofSeconds(HEALTH_CHECK_TIMEOUT_SECONDS));
        healthRequestFactory.setReadTimeout(Duration.ofSeconds(HEALTH_CHECK_TIMEOUT_SECONDS));

        this.healthCheckClient = restClientBuilder
                .baseUrl(separatorUrl)
                .requestFactory(healthRequestFactory)
                .build();
    }

    public record SeparationResult(
            String instrumentalPath,
            String vocalPath,
            long processingTimeMs
    ) {}

    public SeparationResult separate(Path audioFilePath, String trackId) {
        log.info("Requesting audio separation for track {} from {}", trackId, audioFilePath);

        var request = Map.of(
                "inputPath", audioFilePath.toString(),
                "trackId", trackId
        );

        long startTime = System.currentTimeMillis();
        Exception lastException = null;

        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            try {
                @SuppressWarnings("unchecked")
                Map<String, String> response = restClient.post()
                        .uri("/api/separate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(request)
                        .retrieve()
                        .body(Map.class);

                long elapsed = System.currentTimeMillis() - startTime;

                if (response == null || !response.containsKey("instrumental_path")) {
                    throw new RuntimeException("Invalid response from audio separator");
                }

                log.info("Audio separation completed for track {} in {}ms (attempt {})",
                        trackId, elapsed, attempt);

                return new SeparationResult(
                        response.get("instrumental_path"),
                        response.get("vocal_path"),
                        elapsed
                );
            } catch (ResourceAccessException e) {
                lastException = e;
                if (isRetryableException(e) && attempt < MAX_RETRIES) {
                    long delay = INITIAL_RETRY_DELAY_MS * (1L << (attempt - 1));
                    log.warn("Audio separation attempt {} failed for track {} (retrying in {}ms): {}",
                            attempt, trackId, delay, e.getMessage());
                    try {
                        Thread.sleep(delay);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException("Audio separation interrupted", ie);
                    }
                } else {
                    log.error("Audio separation failed for track {} after {} attempts: {}",
                            trackId, attempt, e.getMessage());
                    throw new RuntimeException("Audio separation failed: " + e.getMessage(), e);
                }
            } catch (Exception e) {
                log.error("Audio separation failed for track {}: {}", trackId, e.getMessage());
                throw new RuntimeException("Audio separation failed: " + e.getMessage(), e);
            }
        }

        throw new RuntimeException("Audio separation failed after " + MAX_RETRIES + " attempts",
                lastException);
    }

    private boolean isRetryableException(ResourceAccessException e) {
        Throwable cause = e.getCause();
        return cause instanceof SocketTimeoutException ||
               cause instanceof java.net.ConnectException ||
               cause instanceof java.io.IOException;
    }

    public boolean isHealthy() {
        try {
            healthCheckClient.get()
                    .uri("/health")
                    .retrieve()
                    .body(String.class);
            return true;
        } catch (Exception e) {
            log.warn("Audio separator health check failed: {}", e.getMessage());
            return false;
        }
    }

    public record ProgressStatus(
            String trackId,
            String state,
            int progress,
            String message,
            SeparationResult result
    ) {}

    public ProgressStatus getProgress(String trackId) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> response = restClient.get()
                    .uri("/api/progress/{trackId}", trackId)
                    .retrieve()
                    .body(Map.class);

            if (response == null) {
                return new ProgressStatus(trackId, "NOT_STARTED", 0, null, null);
            }

            String state = (String) response.get("state");
            int progress = response.get("progress") != null ? ((Number) response.get("progress")).intValue() : 0;
            String message = (String) response.get("message");

            SeparationResult result = null;
            @SuppressWarnings("unchecked")
            Map<String, Object> resultMap = (Map<String, Object>) response.get("result");
            if (resultMap != null) {
                result = new SeparationResult(
                        (String) resultMap.get("instrumental_path"),
                        (String) resultMap.get("vocal_path"),
                        resultMap.get("processing_time_ms") != null
                                ? ((Number) resultMap.get("processing_time_ms")).longValue()
                                : 0
                );
            }

            return new ProgressStatus(trackId, state, progress, message, result);
        } catch (Exception e) {
            log.warn("Failed to get progress for track {}: {}", trackId, e.getMessage());
            return new ProgressStatus(trackId, "NOT_STARTED", 0, null, null);
        }
    }
}
