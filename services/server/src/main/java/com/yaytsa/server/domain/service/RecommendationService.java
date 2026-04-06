package com.yaytsa.server.domain.service;

import com.yaytsa.server.domain.service.CandidateRetrievalService.NeverPlayedFilters;
import com.yaytsa.server.domain.service.CandidateRetrievalService.TrackCandidate;
import com.yaytsa.server.domain.util.EmbeddingUtils;
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
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class RecommendationService {

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
  private static final int MAX_TRACKS_PER_ARTIST = 3;

  private static final double HALF_LIFE_DAYS = 30.0;
  private static final double MMR_LAMBDA = 0.6;
  private static final double GENRE_MATCH_BONUS = 0.08;

  private static final double PENALTY_SESSION_SKIPPED_TRACK = -0.5;
  private static final double PENALTY_SESSION_SKIPPED_ARTIST = -0.3;
  private static final double PENALTY_SESSION_SKIPPED_SIMILAR_MOOD = -0.15;
  private static final double PENALTY_DISLIKED_ARTIST = -0.5;

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
      float anchorWeight,
      List<String> preferGenres,
      List<String> avoidGenres,
      SessionSkipContext sessionSkips) {

    public static RecommendationContext standard(
        float targetEnergy,
        float targetValence,
        float explorationWeight,
        UUID lastPlayedTrackId,
        Set<UUID> recentlyPlayedIds,
        Set<UUID> overplayedIds,
        Set<UUID> excludeIds,
        Set<String> avoidArtists,
        List<String> preferGenres,
        List<String> avoidGenres) {
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
          0.0f,
          preferGenres != null ? preferGenres : List.of(),
          avoidGenres != null ? avoidGenres : List.of(),
          SessionSkipContext.EMPTY);
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
        float anchorWeight,
        List<String> preferGenres,
        List<String> avoidGenres,
        SessionSkipContext sessionSkips) {
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
          anchorWeight,
          preferGenres != null ? preferGenres : List.of(),
          avoidGenres != null ? avoidGenres : List.of(),
          sessionSkips != null ? sessionSkips : SessionSkipContext.EMPTY);
    }
  }

  public record ScoredTrack(
      TrackCandidate candidate, double score, String source, long durationMs) {}

  public List<ScoredTrack> recommend(UUID userId, RecommendationContext ctx, int count) {
    TasteProfileEntity profile = tasteProfileService.getProfile(userId);
    Set<UUID> dislikedIds = new HashSet<>(affinityRepository.findDislikedTrackIds(userId));
    Set<String> dislikedArtists = new HashSet<>(affinityRepository.findDislikedArtistNames(userId));
    Map<UUID, Double> affinityScores = loadAffinityScores(userId);

    var userEmbFuture =
        CompletableFuture.supplyAsync(
                () -> retrieveUserEmbeddingChannel(profile, ctx, count), recommendationExecutor)
            .exceptionally(
                ex -> {
                  log.warn("User embedding channel failed: {}", ex.getMessage());
                  return List.of();
                });
    var sessionFuture =
        CompletableFuture.supplyAsync(
                () -> retrieveSessionFilterChannel(ctx, count), recommendationExecutor)
            .exceptionally(
                ex -> {
                  log.warn("Session filter channel failed: {}", ex.getMessage());
                  return List.of();
                });
    var transitionFuture =
        CompletableFuture.supplyAsync(
                () -> retrieveTransitionChannel(ctx, count), recommendationExecutor)
            .exceptionally(
                ex -> {
                  log.warn("Transition channel failed: {}", ex.getMessage());
                  return List.of();
                });
    var explorationFuture =
        CompletableFuture.supplyAsync(
                () -> retrieveExplorationChannel(userId, ctx, count), recommendationExecutor)
            .exceptionally(
                ex -> {
                  log.warn("Exploration channel failed: {}", ex.getMessage());
                  return List.of();
                });

    CompletableFuture.allOf(userEmbFuture, sessionFuture, transitionFuture, explorationFuture)
        .join();

    Map<UUID, ScoredTrack> candidates = new LinkedHashMap<>();
    for (var st : userEmbFuture.join()) candidates.putIfAbsent(st.candidate().id(), st);
    for (var st : sessionFuture.join()) candidates.putIfAbsent(st.candidate().id(), st);
    for (var st : transitionFuture.join()) candidates.putIfAbsent(st.candidate().id(), st);
    for (var st : explorationFuture.join()) candidates.putIfAbsent(st.candidate().id(), st);

    if (candidates.isEmpty()) {
      log.error("All recommendation channels returned empty for user {}", userId);
      return List.of();
    }

    boolean hasGenrePrefs = !ctx.preferGenres().isEmpty();
    Map<UUID, Set<String>> candidateGenres =
        hasGenrePrefs ? loadGenresForCandidates(candidates.keySet()) : Map.of();
    Set<String> preferGenresLower =
        hasGenrePrefs
            ? ctx.preferGenres().stream()
                .map(String::toLowerCase)
                .collect(java.util.stream.Collectors.toSet())
            : Set.of();

    List<ScoredTrack> scored =
        candidates.values().stream()
            .map(
                st ->
                    new ScoredTrack(
                        st.candidate(),
                        computeScore(
                            st,
                            ctx,
                            affinityScores,
                            dislikedIds,
                            dislikedArtists,
                            candidateGenres,
                            preferGenresLower),
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
    String embedding = EmbeddingUtils.format(queryEmbedding);
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
            ctx.avoidGenres().isEmpty() ? null : ctx.avoidGenres(),
            ctx.preferGenres().isEmpty() ? null : ctx.preferGenres(),
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
      String embedding = EmbeddingUtils.format(features.getEmbeddingMert());
      rows =
          trackFeaturesRepository.findSimilarTracksByMert(
              ctx.lastPlayedTrackId(), embedding, (int) (count * 1.5));
    } else if (features.getEmbeddingDiscogs() != null) {
      String embedding = EmbeddingUtils.format(features.getEmbeddingDiscogs());
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
            ctx.preferGenres().isEmpty() ? null : ctx.preferGenres(),
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
    if (userEmb == null) return EmbeddingUtils.l2Normalize(anchor);
    if (anchor.length != userEmb.length) {
      log.warn(
          "Embedding dimension mismatch: anchor={}, user={}. Using anchor only.",
          anchor.length,
          userEmb.length);
      return EmbeddingUtils.l2Normalize(anchor);
    }
    return blendEmbeddings(anchor, userEmb, weight);
  }

  private static float[] blendEmbeddings(float[] anchor, float[] user, float alpha) {
    float[] result = new float[anchor.length];
    for (int i = 0; i < anchor.length; i++) {
      result[i] = alpha * anchor[i] + (1 - alpha) * user[i];
    }
    return EmbeddingUtils.l2Normalize(result);
  }

  private double computeScore(
      ScoredTrack st,
      RecommendationContext ctx,
      Map<UUID, Double> affinityScores,
      Set<UUID> dislikedIds,
      Set<String> dislikedArtists,
      Map<UUID, Set<String>> candidateGenres,
      Set<String> preferGenresLower) {
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

    if (!preferGenresLower.isEmpty()) {
      Set<String> trackGenres = candidateGenres.getOrDefault(trackId, Set.of());
      boolean hasMatch = trackGenres.stream().anyMatch(preferGenresLower::contains);
      if (hasMatch) score += GENRE_MATCH_BONUS;
    }

    if (ctx.recentlyPlayedIds().contains(trackId)) score += PENALTY_RECENTLY_PLAYED;
    if (ctx.overplayedIds().contains(trackId)) score += PENALTY_OVERPLAYED;
    if (dislikedIds.contains(trackId)) score += PENALTY_THUMBS_DOWN;

    String artist = st.candidate().artistName();
    if (artist != null && dislikedArtists.contains(artist)) score += PENALTY_DISLIKED_ARTIST;

    SessionSkipContext skips = ctx.sessionSkips();
    if (skips != null && !skips.skippedTrackIds().isEmpty()) {
      if (skips.skippedTrackIds().contains(trackId)) {
        score += PENALTY_SESSION_SKIPPED_TRACK;
      }
      if (artist != null && skips.skippedArtistNames().contains(artist)) {
        score += PENALTY_SESSION_SKIPPED_ARTIST;
      }
      if (skips.hasSkipMood()
          && st.candidate().energy() != null
          && st.candidate().valence() != null) {
        double energyDiff = Math.abs(st.candidate().energy() - skips.avgSkippedEnergy());
        double valenceDiff = Math.abs(st.candidate().valence() - skips.avgSkippedValence());
        if (energyDiff < 0.15 && valenceDiff < 0.15) {
          score += PENALTY_SESSION_SKIPPED_SIMILAR_MOOD;
        }
      }
    }

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
    Map<UUID, float[]> embeddings = loadEmbeddingsForCandidates(scored);

    List<ScoredTrack> result = new ArrayList<>();
    Set<UUID> selectedIds = new HashSet<>();
    List<String> recentArtists = new ArrayList<>();
    Map<String, Integer> artistCounts = new HashMap<>();
    List<ScoredTrack> remaining = new ArrayList<>(scored);

    while (result.size() < count && !remaining.isEmpty()) {
      ScoredTrack best = null;
      double bestMmr = Double.NEGATIVE_INFINITY;
      int bestIdx = -1;

      for (int i = 0; i < remaining.size(); i++) {
        var st = remaining.get(i);
        UUID trackId = st.candidate().id();

        if (selectedIds.contains(trackId)) continue;
        if (dislikedIds.contains(trackId)) continue;

        String artist = st.candidate().artistName();
        if (artist != null && ctx.avoidArtists().contains(artist)) continue;
        if (artist != null && wouldExceedConsecutiveArtist(recentArtists, artist)) continue;
        if (artist != null && artistCounts.getOrDefault(artist, 0) >= MAX_TRACKS_PER_ARTIST)
          continue;

        double maxSimToSelected;
        float[] candidateEmb = embeddings.get(trackId);
        if (candidateEmb == null || result.isEmpty()) {
          maxSimToSelected = result.isEmpty() ? 0 : 0.5;
        } else {
          maxSimToSelected = 0;
          for (var selected : result) {
            float[] selEmb = embeddings.get(selected.candidate().id());
            if (selEmb != null) {
              maxSimToSelected = Math.max(maxSimToSelected, cosineSimilarity(candidateEmb, selEmb));
            }
          }
        }

        double mmrScore = MMR_LAMBDA * st.score() - (1 - MMR_LAMBDA) * maxSimToSelected;
        if (mmrScore > bestMmr) {
          bestMmr = mmrScore;
          best = st;
          bestIdx = i;
        }
      }

      if (best == null) break;

      result.add(best);
      selectedIds.add(best.candidate().id());
      String artist = best.candidate().artistName();
      if (artist != null) {
        recentArtists.add(artist);
        artistCounts.merge(artist, 1, Integer::sum);
      }
      remaining.remove(bestIdx);
    }

    return result;
  }

  private Map<UUID, Set<String>> loadGenresForCandidates(Set<UUID> trackIds) {
    if (trackIds.isEmpty()) return Map.of();
    Map<UUID, Set<String>> genreMap = new HashMap<>();
    var rows = trackFeaturesRepository.findGenresByTrackIds(new ArrayList<>(trackIds));
    for (var row : rows) {
      UUID trackId = (UUID) row[0];
      String genre = ((String) row[1]).toLowerCase();
      genreMap.computeIfAbsent(trackId, k -> new HashSet<>()).add(genre);
    }
    return genreMap;
  }

  private Map<UUID, float[]> loadEmbeddingsForCandidates(List<ScoredTrack> scored) {
    List<UUID> trackIds = scored.stream().map(st -> st.candidate().id()).toList();
    if (trackIds.isEmpty()) return Map.of();

    Map<UUID, float[]> embeddings = new HashMap<>();
    var rows = trackFeaturesRepository.findMertEmbeddingsByTrackIds(trackIds);
    for (var row : rows) {
      UUID trackId = (UUID) row[0];
      float[] embedding = EmbeddingUtils.parse(row[1]);
      if (embedding != null) {
        embeddings.put(trackId, embedding);
      }
    }
    return embeddings;
  }

  private static double cosineSimilarity(float[] a, float[] b) {
    if (a.length != b.length) return 0;
    double dot = 0, normA = 0, normB = 0;
    for (int i = 0; i < a.length; i++) {
      dot += a[i] * b[i];
      normA += a[i] * a[i];
      normB += b[i] * b[i];
    }
    double denom = Math.sqrt(normA) * Math.sqrt(normB);
    return denom < 1e-9 ? 0 : dot / denom;
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
}
