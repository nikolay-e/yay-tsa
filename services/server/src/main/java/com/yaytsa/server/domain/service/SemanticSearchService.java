package com.yaytsa.server.domain.service;

import com.yaytsa.server.domain.service.CandidateRetrievalService.TrackCandidate;
import com.yaytsa.server.domain.util.EmbeddingUtils;
import com.yaytsa.server.infrastructure.client.EmbeddingExtractionClient;
import com.yaytsa.server.infrastructure.persistence.repository.TrackFeaturesRepository;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Semaphore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
@Slf4j
public class SemanticSearchService {

  private static final Semaphore SEARCH_SEMAPHORE = new Semaphore(3);

  private final EmbeddingExtractionClient embeddingClient;
  private final TrackFeaturesRepository trackFeaturesRepository;

  public SemanticSearchService(
      EmbeddingExtractionClient embeddingClient, TrackFeaturesRepository trackFeaturesRepository) {
    this.embeddingClient = embeddingClient;
    this.trackFeaturesRepository = trackFeaturesRepository;
  }

  public List<TrackCandidate> searchByText(String query, int limit) {
    if (!embeddingClient.isAvailable()) {
      log.debug("Embedding extractor not available for semantic search");
      return Collections.emptyList();
    }

    if (!SEARCH_SEMAPHORE.tryAcquire()) {
      log.warn(
          "Semantic search rate limited, {} queries in flight",
          3 - SEARCH_SEMAPHORE.availablePermits());
      return Collections.emptyList();
    }

    try {
      List<Float> embedding = embeddingClient.encodeText(query);
      String embeddingStr = EmbeddingUtils.format(embedding);
      var rows = trackFeaturesRepository.findTracksByTextEmbedding(embeddingStr, limit);
      return rows.stream().map(CandidateRetrievalService::mapEmbeddingSimilarityRow).toList();
    } catch (Exception e) {
      log.warn("Semantic search failed for query '{}': {}", query, e.getMessage());
      return Collections.emptyList();
    } finally {
      SEARCH_SEMAPHORE.release();
    }
  }
}
