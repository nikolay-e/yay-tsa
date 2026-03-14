package com.yaytsa.server.controller;

import com.yaytsa.server.domain.service.CandidateRetrievalService;
import com.yaytsa.server.domain.service.CandidateRetrievalService.TrackCandidate;
import com.yaytsa.server.domain.service.RecommendationService;
import com.yaytsa.server.domain.service.RecommendationService.RecommendationContext;
import com.yaytsa.server.domain.service.RecommendationService.ScoredTrack;
import com.yaytsa.server.domain.service.SemanticSearchService;
import com.yaytsa.server.dto.response.RecommendedTrackResponse;
import com.yaytsa.server.dto.response.RecommendedTrackResponse.TrackFeaturesSnippet;
import com.yaytsa.server.infrastructure.security.AuthenticatedUser;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/v1/recommend")
public class RecommendationController {

  private final RecommendationService recommendationService;
  private final SemanticSearchService semanticSearchService;
  private final CandidateRetrievalService candidateRetrievalService;

  public RecommendationController(
      RecommendationService recommendationService,
      SemanticSearchService semanticSearchService,
      CandidateRetrievalService candidateRetrievalService) {
    this.recommendationService = recommendationService;
    this.semanticSearchService = semanticSearchService;
    this.candidateRetrievalService = candidateRetrievalService;
  }

  @GetMapping
  public ResponseEntity<List<RecommendedTrackResponse>> recommend(
      @AuthenticationPrincipal AuthenticatedUser user,
      @RequestParam(defaultValue = "10") int count,
      @RequestParam(defaultValue = "0.5") float energy,
      @RequestParam(defaultValue = "0.5") float valence,
      @RequestParam(defaultValue = "0.3") float exploration,
      @RequestParam(required = false) UUID lastPlayedTrackId) {

    count = Math.clamp(count, 1, 100);
    energy = Math.clamp(energy, 0f, 1f);
    valence = Math.clamp(valence, 0f, 1f);
    exploration = Math.clamp(exploration, 0f, 1f);

    UUID userId = user.getUserEntity().getId();
    Set<UUID> recentlyPlayed =
        new HashSet<>(candidateRetrievalService.getRecentlyPlayedTrackIds(userId, 6));
    Set<UUID> overplayed =
        new HashSet<>(candidateRetrievalService.getRecentlyOverplayed(userId, 24));

    var ctx =
        new RecommendationContext(
            energy,
            valence,
            exploration,
            lastPlayedTrackId,
            recentlyPlayed,
            overplayed,
            recentlyPlayed,
            Set.of());

    List<ScoredTrack> tracks = recommendationService.recommend(userId, ctx, count);
    List<RecommendedTrackResponse> response = tracks.stream().map(this::toResponse).toList();

    return ResponseEntity.ok(response);
  }

  @GetMapping("/search")
  public ResponseEntity<List<RecommendedTrackResponse>> semanticSearch(
      @AuthenticationPrincipal AuthenticatedUser user,
      @RequestParam String q,
      @RequestParam(defaultValue = "20") int limit) {

    limit = Math.clamp(limit, 1, 100);
    List<TrackCandidate> results = semanticSearchService.searchByText(q, limit);
    List<RecommendedTrackResponse> response =
        results.stream()
            .map(
                c ->
                    new RecommendedTrackResponse(
                        c.id(),
                        c.name(),
                        c.artistName(),
                        c.albumName(),
                        0,
                        c.similarity() != null ? c.similarity() : 0,
                        "semantic_search",
                        toFeatures(c)))
            .toList();

    return ResponseEntity.ok(response);
  }

  private RecommendedTrackResponse toResponse(ScoredTrack st) {
    var c = st.candidate();
    return new RecommendedTrackResponse(
        c.id(),
        c.name(),
        c.artistName(),
        c.albumName(),
        st.durationMs(),
        st.score(),
        st.source(),
        toFeatures(c));
  }

  private TrackFeaturesSnippet toFeatures(TrackCandidate c) {
    return new TrackFeaturesSnippet(
        c.bpm(), c.energy(), c.valence(), c.arousal(), c.danceability());
  }
}
