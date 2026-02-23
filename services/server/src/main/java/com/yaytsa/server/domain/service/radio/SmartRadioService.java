package com.yaytsa.server.domain.service.radio;

import com.yaytsa.server.infrastructure.persistence.entity.TrackAudioFeaturesEntity;
import com.yaytsa.server.infrastructure.persistence.repository.PlayHistoryRepository;
import com.yaytsa.server.infrastructure.persistence.repository.TrackAudioFeaturesRepository;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

@Service
public class SmartRadioService {

  private final TrackAudioFeaturesRepository featuresRepository;
  private final PlayHistoryRepository playHistoryRepository;

  public SmartRadioService(
      TrackAudioFeaturesRepository featuresRepository,
      PlayHistoryRepository playHistoryRepository) {
    this.featuresRepository = featuresRepository;
    this.playHistoryRepository = playHistoryRepository;
  }

  private static final int MAX_CANDIDATES = 500;

  public List<UUID> generateRadio(
      UUID userId, String mood, String language, Short minEnergy, Short maxEnergy, int count) {

    count = Math.max(1, Math.min(count, 100));

    // 1. Load user taste profile from recent history
    List<UUID> recentItemIds =
        playHistoryRepository.findRecentItemIdsByUser(userId, PageRequest.of(0, 50));
    Set<UUID> recentSet = new HashSet<>(recentItemIds);

    // Build taste profile from recent listened features
    TasteProfile profile = buildTasteProfile(recentItemIds);

    // 2. Get analyzed tracks matching filters (capped to prevent memory issues)
    List<TrackAudioFeaturesEntity> candidates =
        featuresRepository.findByFilters(mood, language, minEnergy, maxEnergy,
            PageRequest.of(0, MAX_CANDIDATES));

    if (candidates.isEmpty()) {
      return List.of();
    }

    // 3. Get last 3 played track IDs for anti-repeat
    Set<UUID> recentLast3 =
        new HashSet<>(
            recentItemIds.stream().limit(3).collect(Collectors.toList()));

    // 4. Score each candidate
    List<ScoredTrack> scored = new ArrayList<>();
    for (TrackAudioFeaturesEntity candidate : candidates) {
      double score = scoreCandidate(candidate, profile, recentSet, recentLast3);
      scored.add(new ScoredTrack(candidate.getItemId(), score));
    }

    // 5. Sort by score descending, take top N * 1.5, then shuffle lightly
    scored.sort(Comparator.comparingDouble(ScoredTrack::score).reversed());

    int poolSize = Math.min(scored.size(), (int) (count * 1.5));
    List<ScoredTrack> pool = new ArrayList<>(scored.subList(0, poolSize));

    // Light shuffle: swap random pairs for naturalness
    ThreadLocalRandom rng = ThreadLocalRandom.current();
    for (int i = 0; i < pool.size() - 1; i += 2) {
      if (rng.nextBoolean()) {
        Collections.swap(pool, i, i + 1);
      }
    }

    return pool.stream()
        .limit(count)
        .map(ScoredTrack::itemId)
        .collect(Collectors.toList());
  }

  private double scoreCandidate(
      TrackAudioFeaturesEntity candidate,
      TasteProfile profile,
      Set<UUID> recentSet,
      Set<UUID> recentLast3) {

    double score = 0;

    // Mood match
    if (profile.dominantMood != null
        && profile.dominantMood.equals(candidate.getMood())) {
      score += 3;
    }

    // Energy proximity (within ±2 of average)
    if (candidate.getEnergy() != null && profile.avgEnergy > 0) {
      int diff = Math.abs(candidate.getEnergy() - (int) Math.round(profile.avgEnergy));
      if (diff <= 2) score += 2;
    }

    // Valence proximity
    if (candidate.getValence() != null && profile.avgValence > 0) {
      int diff = Math.abs(candidate.getValence() - (int) Math.round(profile.avgValence));
      if (diff <= 2) score += 1;
    }

    // Recently played penalty
    if (recentSet.contains(candidate.getItemId())) {
      score -= 2;
    }

    // Last 3 tracks penalty (avoid immediate repeats)
    if (recentLast3.contains(candidate.getItemId())) {
      score -= 5;
    }

    // Random factor for variety
    score += ThreadLocalRandom.current().nextDouble(0, 2);

    return score;
  }

  private TasteProfile buildTasteProfile(List<UUID> recentItemIds) {
    if (recentItemIds.isEmpty()) {
      return new TasteProfile(null, 0, 0);
    }

    List<TrackAudioFeaturesEntity> recentFeatures =
        featuresRepository.findAllById(recentItemIds);

    if (recentFeatures.isEmpty()) {
      return new TasteProfile(null, 0, 0);
    }

    // Find dominant mood
    Map<String, Long> moodCounts =
        recentFeatures.stream()
            .filter(f -> f.getMood() != null)
            .collect(Collectors.groupingBy(TrackAudioFeaturesEntity::getMood, Collectors.counting()));
    String dominantMood =
        moodCounts.entrySet().stream()
            .max(Map.Entry.comparingByValue())
            .map(Map.Entry::getKey)
            .orElse(null);

    // Average energy
    double avgEnergy =
        recentFeatures.stream()
            .filter(f -> f.getEnergy() != null)
            .mapToInt(TrackAudioFeaturesEntity::getEnergy)
            .average()
            .orElse(0);

    // Average valence
    double avgValence =
        recentFeatures.stream()
            .filter(f -> f.getValence() != null)
            .mapToInt(TrackAudioFeaturesEntity::getValence)
            .average()
            .orElse(0);

    return new TasteProfile(dominantMood, avgEnergy, avgValence);
  }

  private record TasteProfile(String dominantMood, double avgEnergy, double avgValence) {}

  private record ScoredTrack(UUID itemId, double score) {}
}
