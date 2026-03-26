package com.yaytsa.server.infrastructure.client;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Duration;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class RadioSeedClient {

  private static final Logger log = LoggerFactory.getLogger(RadioSeedClient.class);

  private final RestClient restClient;

  public RadioSeedClient(
      RestClient.Builder restClientBuilder,
      @Value("${yaytsa.media.karaoke.separator-url:http://audio-separator:8000}") String baseUrl) {
    var factory = new SimpleClientHttpRequestFactory();
    factory.setConnectTimeout(Duration.ofSeconds(10));
    factory.setReadTimeout(Duration.ofSeconds(30));
    this.restClient = restClientBuilder.baseUrl(baseUrl).requestFactory(factory).build();
  }

  public record SeedTrackInput(
      @JsonProperty("track_id") String trackId,
      @JsonProperty("embedding_mert") List<Float> embeddingMert,
      @JsonProperty("affinity_score") double affinityScore) {}

  public record SeedResult(@JsonProperty("track_id") String trackId) {}

  private record ComputeSeedsRequest(
      @JsonProperty("tracks") List<SeedTrackInput> tracks,
      @JsonProperty("num_seeds") int numSeeds) {}

  private record ComputeSeedsResponse(@JsonProperty("seeds") List<SeedResult> seeds) {}

  public List<SeedResult> computeSeeds(List<SeedTrackInput> tracks, int numSeeds) {
    var request = new ComputeSeedsRequest(tracks, numSeeds);
    try {
      var response =
          restClient
              .post()
              .uri("/api/v1/radio/compute-seeds")
              .contentType(MediaType.APPLICATION_JSON)
              .body(request)
              .retrieve()
              .body(ComputeSeedsResponse.class);
      if (response == null || response.seeds() == null) return List.of();
      return response.seeds();
    } catch (Exception e) {
      log.error("Failed to compute radio seeds: {}", e.getMessage());
      return List.of();
    }
  }
}
