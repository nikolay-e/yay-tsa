package com.yaytsa.server.domain.service.metadata;

import jakarta.annotation.PreDestroy;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Aggregated metadata enrichment service.
 *
 * <p>Queries multiple metadata providers (MusicBrainz, Last.fm, Spotify) in parallel, compares
 * results, and returns the best match based on confidence scoring and provider priority.
 */
@Service
public class AggregatedMetadataService {

  private static final Logger log = LoggerFactory.getLogger(AggregatedMetadataService.class);

  private final List<MetadataProvider> providers;
  private final ExecutorService executor;

  public AggregatedMetadataService(List<MetadataProvider> providers) {
    this.providers = providers;
    this.executor = Executors.newVirtualThreadPerTaskExecutor(); // Java 21 virtual threads

    log.info(
        "Initialized metadata enrichment with {} providers: {}",
        providers.size(),
        providers.stream()
            .filter(MetadataProvider::isEnabled)
            .map(MetadataProvider::getProviderName)
            .toList());
  }

  @PreDestroy
  public void shutdown() {
    log.info("Shutting down metadata enrichment executor");
    executor.shutdown();
    try {
      if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
        executor.shutdownNow();
      }
    } catch (InterruptedException e) {
      executor.shutdownNow();
      Thread.currentThread().interrupt();
    }
  }

  /**
   * Enriches metadata by querying all enabled providers in parallel.
   *
   * @param artist Artist name from file tags
   * @param title Track title from file tags
   * @return Best enriched metadata result, or empty if no providers found a match
   */
  public Optional<MetadataProvider.EnrichedMetadata> enrichMetadata(String artist, String title) {
    if (artist == null || artist.isBlank() || title == null || title.isBlank()) {
      log.debug("Skipping metadata enrichment - artist or title is blank");
      return Optional.empty();
    }

    List<MetadataProvider> enabledProviders =
        providers.stream().filter(MetadataProvider::isEnabled).toList();

    if (enabledProviders.isEmpty()) {
      log.warn("No metadata providers are enabled");
      return Optional.empty();
    }

    log.info(
        "Querying {} providers for: {} - {}",
        enabledProviders.size(),
        artist,
        title);

    // Query all providers in parallel
    List<CompletableFuture<Optional<MetadataProvider.EnrichedMetadata>>> futures =
        enabledProviders.stream()
            .map(
                provider ->
                    CompletableFuture.supplyAsync(
                        () -> {
                          try {
                            return provider.findMetadata(artist, title);
                          } catch (Exception e) {
                            log.error(
                                "Error querying provider {}: {}",
                                provider.getProviderName(),
                                e.getMessage());
                            return Optional.<MetadataProvider.EnrichedMetadata>empty();
                          }
                        },
                        executor))
            .toList();

    // Wait for all queries to complete (with timeout)
    try {
      CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
          .orTimeout(15, TimeUnit.SECONDS)
          .join();
    } catch (Exception e) {
      log.warn("Metadata enrichment timed out or failed: {}", e.getMessage());
    }

    // Collect results
    List<MetadataProvider.EnrichedMetadata> results = new ArrayList<>();
    for (CompletableFuture<Optional<MetadataProvider.EnrichedMetadata>> future : futures) {
      future.join().ifPresent(results::add);
    }

    if (results.isEmpty()) {
      log.info("No metadata found from any provider for: {} - {}", artist, title);
      return Optional.empty();
    }

    // Log all results for debugging
    log.info("Found {} result(s) from providers:", results.size());
    for (MetadataProvider.EnrichedMetadata result : results) {
      log.info(
          "  - {}: {} - {} ({}) [confidence: {}]",
          result.source(),
          result.artist(),
          result.album(),
          result.year(),
          result.confidence());
    }

    // Select best result based on confidence and provider priority
    MetadataProvider.EnrichedMetadata best = selectBestResult(results, enabledProviders);

    // Merge coverArtUrl from other providers if the best result doesn't have one.
    // MusicBrainz provides Cover Art Archive URLs (CC-licensed), even if it loses on overall score.
    MetadataProvider.EnrichedMetadata merged = best;
    if (best.coverArtUrl() == null) {
      merged = results.stream()
          .filter(r -> r.coverArtUrl() != null && !r.source().equals(best.source()))
          .findFirst()
          .map(r -> {
            log.info("Merging coverArtUrl from {} into best result from {}", r.source(), best.source());
            return best.withCoverArtUrl(r.coverArtUrl());
          })
          .orElse(best);
    }

    log.info(
        "Selected best match from {}: {} - {} ({}) coverArtUrl={} [confidence: {}]",
        merged.source(),
        merged.artist(),
        merged.album(),
        merged.year(),
        merged.coverArtUrl() != null ? "yes" : "no",
        merged.confidence());

    return Optional.of(merged);
  }

  /**
   * Selects the best result from multiple providers.
   *
   * <p>Scoring algorithm: 1. Calculate weighted score = confidence * (priority / 100) 2. Sort by
   * weighted score (descending) 3. Return highest scoring result
   *
   * <p>This favors high-confidence results from high-priority providers.
   */
  private MetadataProvider.EnrichedMetadata selectBestResult(
      List<MetadataProvider.EnrichedMetadata> results, List<MetadataProvider> enabledProviders) {

    // Build provider priority map
    var priorityMap =
        enabledProviders.stream()
            .collect(
                java.util.stream.Collectors.toMap(
                    MetadataProvider::getProviderName, MetadataProvider::getPriority));

    // Score each result
    record ScoredResult(MetadataProvider.EnrichedMetadata metadata, double score) {}

    List<ScoredResult> scored =
        results.stream()
            .map(
                metadata -> {
                  int priority = priorityMap.getOrDefault(metadata.source(), 50);
                  double score = metadata.confidence() * (priority / 100.0);
                  return new ScoredResult(metadata, score);
                })
            .sorted(Comparator.comparingDouble(ScoredResult::score).reversed())
            .toList();

    // Log scoring details
    log.debug("Result scoring:");
    for (ScoredResult scoredResult : scored) {
      log.debug(
          "  - {} (confidence={}, priority={}, score={})",
          scoredResult.metadata.source(),
          scoredResult.metadata.confidence(),
          priorityMap.get(scoredResult.metadata.source()),
          scoredResult.score);
    }

    return scored.get(0).metadata();
  }

  /**
   * Checks consensus across multiple providers.
   *
   * <p>If multiple providers agree on album name (ignoring case and whitespace), boost confidence.
   *
   * @param results Results from different providers
   * @return Best result with adjusted confidence
   */
  @SuppressWarnings("unused")
  private MetadataProvider.EnrichedMetadata applyConsensusBoost(
      List<MetadataProvider.EnrichedMetadata> results) {

    if (results.size() < 2) {
      return results.get(0);
    }

    // Count album name occurrences (normalized)
    var albumCounts = new java.util.HashMap<String, Integer>();
    for (MetadataProvider.EnrichedMetadata result : results) {
      if (result.album() != null) {
        String normalized = result.album().toLowerCase().replaceAll("\\s+", "");
        albumCounts.merge(normalized, 1, Integer::sum);
      }
    }

    // Find most common album
    String mostCommonAlbum =
        albumCounts.entrySet().stream()
            .max(Comparator.comparingInt(java.util.Map.Entry::getValue))
            .map(java.util.Map.Entry::getKey)
            .orElse(null);

    if (mostCommonAlbum == null) {
      return results.get(0);
    }

    int consensusCount = albumCounts.get(mostCommonAlbum);

    // Boost confidence if multiple providers agree
    double consensusBoost = consensusCount > 1 ? 0.1 * (consensusCount - 1) : 0.0;

    // Return highest confidence result with consensus boost
    return results.stream()
        .filter(
            r ->
                r.album() != null
                    && r.album().toLowerCase().replaceAll("\\s+", "").equals(mostCommonAlbum))
        .max(Comparator.comparingDouble(MetadataProvider.EnrichedMetadata::confidence))
        .map(r -> r.withConfidence(Math.min(1.0, r.confidence() + consensusBoost)))
        .orElse(results.get(0));
  }
}
