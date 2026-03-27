package com.yaytsa.server.domain.service;

import com.yaytsa.server.domain.service.CandidateRetrievalService.NeverPlayedFilters;
import com.yaytsa.server.domain.service.CandidateRetrievalService.TrackCandidate;
import com.yaytsa.server.infrastructure.persistence.entity.TasteProfileEntity;
import com.yaytsa.server.infrastructure.persistence.entity.UserTrackAffinityEntity;
import com.yaytsa.server.infrastructure.persistence.repository.AudioTrackRepository;
import com.yaytsa.server.infrastructure.persistence.repository.TrackFeaturesRepository;
import com.yaytsa.server.infrastructure.persistence.repository.UserTrackAffinityRepository;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.stream.IntStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class RecommendationService {

  private static final Logger log = LoggerFactory.getLogger(RecommendationService.class);
  private static final UUID EMPTY_UUID = UUID.fromString("00000000-0000-0000-0000-000000000000");

  private static final double W_AFFINITY = 0.25;
  private static final double W_USER_SIMILARITY = 0.30;
  private static final double W_SESSION_CONTEXT = 0.20;
  private static final double W_TRANSITION = 0.10;
  private static final double W_NOVELTY = 0.15;

  private static final double PENALTY_RECENTLY_PLAYED = -0.4;
  private static final double PENALTY_OVERPLAYED = -0.8;
  private static final double PENALTY_THUMBS_DOWN = -1.0;
  private static final int MAX_CONSECUTIVE_SAME_ARTIST = 2;

  private static final double HALF_LIFE_DAYS = 30.0;

  private final CandidateRetrievalService candidateService;
  private final TasteProfileService tasteProfileService;
  private final UserTrackAffinityRepository affinityRepository;
  private final TrackFeaturesRepository trackFeaturesRepository;
  private final AudioTrackRepository audioTrackRepository;
  private final Executor recommendationExecutor;

  public RecommendationService(
      CandidateRetrievalService candidateService,
      TasteProfileService tasteProfileService,
      UserTrackAffinityRepository affinityRepository,
      TrackFeaturesRepository trackFeaturesRepository,
      AudioTrackRepository audioTrackRepository,
      @org.springframework.beans.factory.annotation.Qualifier("recommendationExecutor")
          Executor recommendationExecutor) {
    this.candidateService = candidateService;
    this.tasteProfileService = tasteProfileService;
    this.affinityRepository = affinityRepository;
    this.trackFeaturesRepository = trackFeaturesRepository;
    this.audioTrackRepository = audioTrackRepository;
    this.recommendationExecutor = recommendationExecutor;
  }

  public record RecommendationContext(
      float targetEnergy,
      float targetValence,
      float explorationWeight,
      UUID lastPlayedTrackId,
      Set<UUID> recentlyPlayedIds,
      Set<UUID> overplayedIds,
      Set<UUID> excludeIds,
      Set<String> avoidArtists,
      float[] anchorEmbedding,
      float anchorWeight) {

    public static RecommendationContext standard(
        float targetEnergy,
        float targetValence,
        float explorationWeight,
        UUID lastPlayedTrackId,
        Set<UUID> recentlyPlayedIds,
        Set<UUID> overplayedIds,
        Set<UUID> excludeIds,
        Set<String> avoidArtists) {
      return new RecommendationContext(
          targetEnergy,
          targetValence,
          explorationWeight,
          lastPlayedTrackId,
          recentlyPlayedIds,
          overplayedIds,
          excludeIds,
          avoidArtists,
          null,
          0.0f);
    }

    public static RecommendationContext radio(
        float targetEnergy,
        float targetValence,
        float explorationWeight,
        UUID lastPlayedTrackId,
        Set<UUID> recentlyPlayedIds,
        Set<UUID> overplayedIds,
        Set<UUID> excludeIds,
        Set<String> avoidArtists,
        float[] anchorEmbedding,
        float anchorWeight) {
      return new RecommendationContext(
          targetEnergy,
          targetValence,
          explorationWeight,
          lastPlayedTrackId,
          recentlyPlayedIds,
          overplayedIds,
          excludeIds,
          avoidArtists,
          anchorEmbedding,
          anchorWeight);
    }
  }

  public record ScoredTrack(
      TrackCandidate candidate, double score, String source, long durationMs) {}

  public List<ScoredTrack> recommend(UUID userId, RecommendationContext ctx, int count) {
    TasteProfileEntity profile = tasteProfileService.getProfile(userId);
    Set<UUID> dislikedIds = new HashSet<>(affinityRepository.findDislikedTrackIds(userId));
    Map<UUID, Double> affinityScores = loadAffinityScores(userId);

    var userEmbFuture =
        CompletableFuture.supplyAsync(
            () -> retrieveUserEmbeddingChannel(profile, ctx, count), recommendationExecutor);
    var sessionFuture =
        CompletableFuture.supplyAsync(
            () -> retrieveSessionFilterChannel(ctx, count), recommendationExecutor);
    var transitionFuture =
        CompletableFuture.supplyAsync(
            () -> retrieveTransitionChannel(ctx, count), recommendationExecutor);
    var explorationFuture =
        CompletableFuture.supplyAsync(
            () -> retrieveExplorationChannel(userId, ctx, count), recommendationExecutor);

    CompletableFuture.allOf(userEmbFuture, sessionFuture, transitionFuture, explorationFuture)
        .join();

    Map<UUID, ScoredTrack> candidates = new LinkedHashMap<>();
    for (var st : userEmbFuture.join()) candidates.putIfAbsent(st.candidate().id(), st);
    for (var st : sessionFuture.join()) candidates.putIfAbsent(st.candidate().id(), st);
    for (var st : transitionFuture.join()) candidates.putIfAbsent(st.candidate().id(), st);
    for (var st : explorationFuture.join()) candidates.putIfAbsent(st.candidate().id(), st);

    List<ScoredTrack> scored =
        candidates.values().stream()
            .map(
                st ->
                    new ScoredTrack(
                        st.candidate(),
                        computeScore(st, ctx, affinityScores, dislikedIds),
                        st.source(),
                        st.durationMs()))
            .sorted(Comparator.comparingDouble(ScoredTrack::score).reversed())
            .toList();

    List<ScoredTrack> reranked = rerank(scored, dislikedIds, ctx, count);

    log.info(
        "Recommended {} tracks for user {} (candidates: {}, channels:"
            + " user_emb/session/transition/explore)",
        reranked.size(),
        userId,
        candidates.size());

    return reranked;
  }

  private List<ScoredTrack> retrieveUserEmbeddingChannel(
      TasteProfileEntity profile, RecommendationContext ctx, int count) {
    float[] queryEmbedding = resolveQueryEmbedding(profile, ctx);
    if (queryEmbedding == null) return List.of();

    int limit = (int) (count * 2.5);
    String embedding = formatEmbedding(queryEmbedding);
    List<UUID> excludeList =
        ctx.excludeIds().isEmpty() ? List.of(EMPTY_UUID) : new ArrayList<>(ctx.excludeIds());

    var rows = trackFeaturesRepository.findTracksByUserEmbedding(embedding, excludeList, limit);
    List<ScoredTrack> results = new ArrayList<>();
    for (var row : rows) {
      var candidate = CandidateRetrievalService.mapEmbeddingSimilarityRow(row);
      if (!ctx.excludeIds().contains(candidate.id())) {
        long duration = getDuration(candidate.id());
        results.add(new ScoredTrack(candidate, 0, "user_embedding", duration));
      }
    }
    return results;
  }

  private List<ScoredTrack> retrieveSessionFilterChannel(RecommendationContext ctx, int count) {
    float energyBand = 0.25f;
    float valenceBand = 0.25f;
    var filters =
        new CandidateRetrievalService.LibrarySearchFilters(
            ctx.targetEnergy() - energyBand,
            ctx.targetEnergy() + energyBand,
            null,
            null,
            ctx.targetValence() - valenceBand,
            ctx.targetValence() + valenceBand,
            null,
            null,
            null,
            null,
            null,
            count * 2);
    var results = candidateService.searchLibrary(filters);
    List<ScoredTrack> tracks = new ArrayList<>();
    for (var c : results) {
      if (!ctx.excludeIds().contains(c.id())) {
        long duration = getDuration(c.id());
        tracks.add(new ScoredTrack(c, 0, "session_filter", duration));
      }
    }
    return tracks;
  }

  private List<ScoredTrack> retrieveTransitionChannel(RecommendationContext ctx, int count) {
    if (ctx.lastPlayedTrackId() == null) return List.of();

    var features = trackFeaturesRepository.findByTrackId(ctx.lastPlayedTrackId()).orElse(null);
    if (features == null) return List.of();

    List<Object[]> rows;
    if (features.getEmbeddingMert() != null) {
      String embedding = formatEmbedding(features.getEmbeddingMert());
      rows =
          trackFeaturesRepository.findSimilarTracksByMert(
              ctx.lastPlayedTrackId(), embedding, (int) (count * 1.5));
    } else if (features.getEmbeddingDiscogs() != null) {
      String embedding = formatEmbedding(features.getEmbeddingDiscogs());
      rows =
          trackFeaturesRepository.findSimilarTracksExact(
              ctx.lastPlayedTrackId(), embedding, (int) (count * 1.5));
    } else {
      return List.of();
    }

    List<ScoredTrack> tracks = new ArrayList<>();
    for (var row : rows) {
      var candidate = CandidateRetrievalService.mapEmbeddingSimilarityRow(row);
      if (!ctx.excludeIds().contains(candidate.id())) {
        long duration = getDuration(candidate.id());
        tracks.add(new ScoredTrack(candidate, 0, "transition", duration));
      }
    }
    return tracks;
  }

  private List<ScoredTrack> retrieveExplorationChannel(
      UUID userId, RecommendationContext ctx, int count) {
    float energyBand = 0.15f;
    var filters =
        new NeverPlayedFilters(
            ctx.targetEnergy() - energyBand,
            ctx.targetEnergy() + energyBand,
            null,
            null,
            null,
            null,
            count);
    var results = candidateService.findNeverPlayedTracks(userId, filters);
    List<ScoredTrack> tracks = new ArrayList<>();
    for (var c : results) {
      if (!ctx.excludeIds().contains(c.id())) {
        long duration = getDuration(c.id());
        tracks.add(new ScoredTrack(c, 0, "exploration", duration));
      }
    }
    return tracks;
  }

  private static float[] resolveQueryEmbedding(
      TasteProfileEntity profile, RecommendationContext ctx) {
    float[] userEmb = profile != null ? profile.getEmbeddingMert() : null;
    float[] anchor = ctx.anchorEmbedding();
    float weight = ctx.anchorWeight();

    if (anchor == null || weight <= 0) return userEmb;
    if (userEmb == null) return l2Normalize(anchor);
    if (anchor.length != userEmb.length) {
      log.warn(
          "Embedding dimension mismatch: anchor={}, user={}. Using anchor only.",
          anchor.length,
          userEmb.length);
      return l2Normalize(anchor);
    }
    return blendEmbeddings(anchor, userEmb, weight);
  }

  private static float[] blendEmbeddings(float[] anchor, float[] user, float alpha) {
    float[] result = new float[anchor.length];
    for (int i = 0; i < anchor.length; i++) {
      result[i] = alpha * anchor[i] + (1 - alpha) * user[i];
    }
    return l2Normalize(result);
  }

  private static float[] l2Normalize(float[] vec) {
    float norm = 0;
    for (float v : vec) norm += v * v;
    norm = (float) Math.sqrt(norm);
    if (norm < 1e-9f) return vec.clone();
    float[] result = new float[vec.length];
    for (int i = 0; i < vec.length; i++) result[i] = vec[i] / norm;
    return result;
  }

  private double computeScore(
      ScoredTrack st,
      RecommendationContext ctx,
      Map<UUID, Double> affinityScores,
      Set<UUID> dislikedIds) {
    UUID trackId = st.candidate().id();
    double score = 0;

    double affinity = affinityScores.getOrDefault(trackId, 0.0);
    score += W_AFFINITY * Math.tanh(affinity);

    if (st.candidate().similarity() != null) {
      score += W_USER_SIMILARITY * st.candidate().similarity();
    }

    double contextMatch = computeContextMatch(st.candidate(), ctx);
    score += W_SESSION_CONTEXT * contextMatch;

    if ("transition".equals(st.source()) && st.candidate().similarity() != null) {
      score += W_TRANSITION * st.candidate().similarity();
    }

    double novelty = affinityScores.containsKey(trackId) ? 0.0 : 1.0;
    score += W_NOVELTY * ctx.explorationWeight() * novelty;

    if (ctx.recentlyPlayedIds().contains(trackId)) score += PENALTY_RECENTLY_PLAYED;
    if (ctx.overplayedIds().contains(trackId)) score += PENALTY_OVERPLAYED;
    if (dislikedIds.contains(trackId)) score += PENALTY_THUMBS_DOWN;

    return score;
  }

  private double computeContextMatch(TrackCandidate c, RecommendationContext ctx) {
    double match = 0;
    int factors = 0;
    if (c.energy() != null) {
      match += 1.0 - Math.abs(c.energy() - ctx.targetEnergy());
      factors++;
    }
    if (c.valence() != null) {
      match += 1.0 - Math.abs(c.valence() - ctx.targetValence());
      factors++;
    }
    return factors > 0 ? match / factors : 0.5;
  }

  private List<ScoredTrack> rerank(
      List<ScoredTrack> scored, Set<UUID> dislikedIds, RecommendationContext ctx, int count) {
    List<ScoredTrack> result = new ArrayList<>();
    Set<UUID> selectedIds = new HashSet<>();
    List<String> recentArtists = new ArrayList<>();

    for (var st : scored) {
      if (result.size() >= count) break;
      UUID trackId = st.candidate().id();

      if (selectedIds.contains(trackId)) continue;
      if (dislikedIds.contains(trackId)) continue;

      String artist = st.candidate().artistName();
      if (artist != null && ctx.avoidArtists().contains(artist)) continue;
      if (artist != null && wouldExceedConsecutiveArtist(recentArtists, artist)) continue;

      result.add(st);
      selectedIds.add(trackId);
      if (artist != null) recentArtists.add(artist);
    }

    return result;
  }

  private boolean wouldExceedConsecutiveArtist(List<String> recentArtists, String artist) {
    int consecutive = 0;
    for (int i = recentArtists.size() - 1; i >= 0; i--) {
      if (artist.equals(recentArtists.get(i))) {
        consecutive++;
      } else {
        break;
      }
    }
    return consecutive >= MAX_CONSECUTIVE_SAME_ARTIST;
  }

  private Map<UUID, Double> loadAffinityScores(UUID userId) {
    Map<UUID, Double> scores = new HashMap<>();
    OffsetDateTime now = OffsetDateTime.now();
    for (var a : affinityRepository.findPositiveByUserId(userId)) {
      scores.put(a.getId().getTrackId(), applyTimeDecay(a, now));
    }
    return scores;
  }

  private static double applyTimeDecay(UserTrackAffinityEntity a, OffsetDateTime now) {
    double implicitScore =
        a.getCompletionCount() * AffinityAggregationService.COMPLETION_WEIGHT
            + a.getPlayCount() * AffinityAggregationService.PLAY_WEIGHT
            + a.getSkipCount() * AffinityAggregationService.SKIP_EARLY_PENALTY;

    if (a.getLastSignalAt() != null) {
      double daysSince = ChronoUnit.HOURS.between(a.getLastSignalAt(), now) / 24.0;
      implicitScore *= Math.pow(0.5, daysSince / HALF_LIFE_DAYS);
    }

    double explicitScore =
        a.getThumbsUpCount() * AffinityAggregationService.THUMBS_UP_WEIGHT
            + a.getThumbsDownCount() * AffinityAggregationService.THUMBS_DOWN_PENALTY;

    return implicitScore + explicitScore;
  }

  private long getDuration(UUID trackId) {
    return audioTrackRepository
        .findById(trackId)
        .map(at -> at.getDurationMs() != null ? at.getDurationMs() : 0L)
        .orElse(0L);
  }

  private static String formatEmbedding(float[] embedding) {
    return IntStream.range(0, embedding.length)
        .mapToObj(i -> String.valueOf(embedding[i]))
        .collect(java.util.stream.Collectors.joining(",", "[", "]"));
  }
}
