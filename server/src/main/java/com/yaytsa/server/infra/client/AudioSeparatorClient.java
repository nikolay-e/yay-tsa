package com.yaytsa.server.infra.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.nio.file.Path;
import java.util.Map;

@Component
public class AudioSeparatorClient {

    private static final Logger log = LoggerFactory.getLogger(AudioSeparatorClient.class);

    private final RestClient restClient;

    public AudioSeparatorClient(
            RestClient.Builder restClientBuilder,
            @Value("${yaytsa.media.karaoke.separator-url:http://audio-separator:8000}") String separatorUrl) {
        this.restClient = restClientBuilder
                .baseUrl(separatorUrl)
                .build();
    }

    public record SeparationResult(
            String instrumentalPath,
            String vocalPath,
            long processingTimeMs
    ) {}

    public SeparationResult separate(Path audioFilePath, Path outputDir, String trackId) {
        log.info("Requesting audio separation for track {} from {}", trackId, audioFilePath);

        var request = Map.of(
                "inputPath", audioFilePath.toString(),
                "outputDir", outputDir.toString(),
                "trackId", trackId
        );

        long startTime = System.currentTimeMillis();

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

            log.info("Audio separation completed for track {} in {}ms", trackId, elapsed);

            return new SeparationResult(
                    response.get("instrumental_path"),
                    response.get("vocal_path"),
                    elapsed
            );
        } catch (Exception e) {
            log.error("Audio separation failed for track {}: {}", trackId, e.getMessage());
            throw new RuntimeException("Audio separation failed: " + e.getMessage(), e);
        }
    }

    public boolean isHealthy() {
        try {
            restClient.get()
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
