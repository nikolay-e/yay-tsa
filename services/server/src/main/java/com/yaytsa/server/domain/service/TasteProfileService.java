package com.yaytsa.server.domain.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yaytsa.server.infrastructure.persistence.entity.TasteProfileEntity;
import com.yaytsa.server.infrastructure.persistence.entity.TrackFeaturesEntity;
import com.yaytsa.server.infrastructure.persistence.entity.UserEntity;
import com.yaytsa.server.infrastructure.persistence.repository.PlaybackSignalRepository;
import com.yaytsa.server.infrastructure.persistence.repository.TasteProfileRepository;
import com.yaytsa.server.infrastructure.persistence.repository.TrackFeaturesRepository;
import com.yaytsa.server.infrastructure.persistence.repository.UserRepository;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TasteProfileService {

  private static final Logger log = LoggerFactory.getLogger(TasteProfileService.class);
  private static final int TOP_TRACKS_LIMIT = 200;
  private static final int TOP_ARTISTS_LIMIT = 10;
  private static final int LOOKBACK_DAYS = 90;

  private final TasteProfileRepository tasteProfileRepository;
  private final PlaybackSignalRepository playbackSignalRepository;
  private final TrackFeaturesRepository trackFeaturesRepository;
  private final UserRepository userRepository;
  private final ObjectMapper objectMapper;

  public TasteProfileService(
      TasteProfileRepository tasteProfileRepository,
      PlaybackSignalRepository playbackSignalRepository,
      TrackFeaturesRepository trackFeaturesRepository,
      UserRepository userRepository,
      ObjectMapper objectMapper) {
    this.tasteProfileRepository = tasteProfileRepository;
    this.playbackSignalRepository = playbackSignalRepository;
    this.trackFeaturesRepository = trackFeaturesRepository;
    this.userRepository = userRepository;
    this.objectMapper = objectMapper;
  }

  @Transactional
  public void rebuildProfile(UUID userId) {
    OffsetDateTime since = OffsetDateTime.now().minusDays(LOOKBACK_DAYS);

    List<Object[]> playCounts =
        playbackSignalRepository.getPlayCountsByUser(userId, since, TOP_TRACKS_LIMIT);

    if (playCounts.isEmpty()) {
      log.debug("No playback data for user {}, skipping taste profile rebuild", userId);
      return;
    }

    List<UUID> topTrackIds =
        playCounts.stream().map(row -> (UUID) row[0]).collect(Collectors.toList());

    Map<UUID, TrackFeaturesEntity> featuresMap = loadFeatures(topTrackIds);
    FeatureAggregation aggregation = aggregateFeatures(playCounts, featuresMap);

    List<Object[]> topArtists =
        playbackSignalRepository.getTopArtistsByUser(userId, since, TOP_ARTISTS_LIMIT);

    List<Object[]> sessionStats = playbackSignalRepository.getSessionStats(userId, since);
    double avgSessionMin = extractAvgSessionMinutes(sessionStats);

    Map<String, Object> profileData = buildProfileData(aggregation, topArtists, avgSessionMin);
    String summaryText = generateSummary(aggregation, topArtists, avgSessionMin);

    saveProfile(userId, profileData, summaryText);

    log.info(
        "Rebuilt taste profile for user {} with {} tracks, {} artists",
        userId,
        topTrackIds.size(),
        topArtists.size());
  }

  @Transactional(readOnly = true)
  public TasteProfileEntity getProfile(UUID userId) {
    return tasteProfileRepository.findById(userId).orElse(null);
  }

  @Transactional
  public void rebuildAllProfiles() {
    List<UserEntity> users = userRepository.findAll();
    int rebuilt = 0;
    for (UserEntity user : users) {
      try {
        rebuildProfile(user.getId());
        rebuilt++;
      } catch (Exception e) {
        log.warn("Failed to rebuild taste profile for user {}: {}", user.getId(), e.getMessage());
      }
    }
    if (rebuilt > 0) {
      log.info("Rebuilt taste profiles for {} users", rebuilt);
    }
  }

  private Map<UUID, TrackFeaturesEntity> loadFeatures(List<UUID> trackIds) {
    Map<UUID, TrackFeaturesEntity> map = new HashMap<>();
    for (UUID trackId : trackIds) {
      trackFeaturesRepository.findByTrackId(trackId).ifPresent(f -> map.put(trackId, f));
    }
    return map;
  }

  private FeatureAggregation aggregateFeatures(
      List<Object[]> playCounts, Map<UUID, TrackFeaturesEntity> featuresMap) {
    List<Float> bpmValues = new ArrayList<>();
    List<Float> energyValues = new ArrayList<>();
    List<Float> valenceValues = new ArrayList<>();
    List<Float> danceabilityValues = new ArrayList<>();
    List<Float> arousalValues = new ArrayList<>();

    for (Object[] row : playCounts) {
      UUID trackId = (UUID) row[0];
      TrackFeaturesEntity features = featuresMap.get(trackId);
      if (features == null) {
        continue;
      }

      long playCount = ((Number) row[1]).longValue();
      int weight = (int) Math.min(playCount, 10);

      for (int i = 0; i < weight; i++) {
        if (features.getBpm() != null) bpmValues.add(features.getBpm());
        if (features.getEnergy() != null) energyValues.add(features.getEnergy());
        if (features.getValence() != null) valenceValues.add(features.getValence());
        if (features.getDanceability() != null) danceabilityValues.add(features.getDanceability());
        if (features.getArousal() != null) arousalValues.add(features.getArousal());
      }
    }

    return new FeatureAggregation(
        bpmValues, energyValues, valenceValues, danceabilityValues, arousalValues);
  }

  private Map<String, Object> buildProfileData(
      FeatureAggregation agg, List<Object[]> topArtists, double avgSessionMin) {
    Map<String, Object> profile = new LinkedHashMap<>();

    profile.put("bpm", rangeMap(agg.bpmValues));
    profile.put("energy", rangeMap(agg.energyValues));
    profile.put("valence", rangeMap(agg.valenceValues));
    profile.put("danceability", rangeMap(agg.danceabilityValues));
    profile.put("arousal", rangeMap(agg.arousalValues));

    List<Map<String, Object>> artists = new ArrayList<>();
    for (Object[] row : topArtists) {
      Map<String, Object> artist = new LinkedHashMap<>();
      artist.put("name", row[0]);
      artist.put("playCount", ((Number) row[1]).longValue());
      artist.put("completions", ((Number) row[2]).longValue());
      artists.add(artist);
    }
    profile.put("topArtists", artists);
    profile.put("avgSessionMinutes", Math.round(avgSessionMin));
    profile.put("lookbackDays", LOOKBACK_DAYS);

    return profile;
  }

  private String generateSummary(
      FeatureAggregation agg, List<Object[]> topArtists, double avgSessionMin) {
    StringBuilder sb = new StringBuilder();

    if (!agg.bpmValues.isEmpty()) {
      float bpmP10 = percentile(agg.bpmValues, 10);
      float bpmP90 = percentile(agg.bpmValues, 90);
      sb.append(String.format("Prefers music in the %.0f-%.0f BPM range", bpmP10, bpmP90));
    }

    if (!agg.energyValues.isEmpty()) {
      float energyMedian = percentile(agg.energyValues, 50);
      sb.append(" with ").append(describeLevel(energyMedian)).append(" energy");
    }

    if (!agg.valenceValues.isEmpty()) {
      float valenceMedian = percentile(agg.valenceValues, 50);
      sb.append(" and ").append(describeValence(valenceMedian)).append(" mood");
    }

    sb.append(".");

    if (!topArtists.isEmpty()) {
      sb.append(" Top artists: ");
      StringJoiner joiner = new StringJoiner(", ");
      int limit = Math.min(topArtists.size(), 5);
      for (int i = 0; i < limit; i++) {
        joiner.add((String) topArtists.get(i)[0]);
      }
      sb.append(joiner).append(".");
    }

    if (avgSessionMin > 0) {
      sb.append(String.format(" Average session length: %.0fmin.", avgSessionMin));
    }

    return sb.toString();
  }

  private void saveProfile(UUID userId, Map<String, Object> profileData, String summaryText) {
    TasteProfileEntity entity =
        tasteProfileRepository.findById(userId).orElseGet(TasteProfileEntity::new);

    if (entity.getUserId() == null) {
      UserEntity user =
          userRepository
              .findById(userId)
              .orElseThrow(() -> new IllegalArgumentException("User not found: " + userId));
      entity.setUser(user);
    }

    try {
      entity.setProfile(objectMapper.writeValueAsString(profileData));
    } catch (JsonProcessingException e) {
      log.error("Failed to serialize taste profile for user {}", userId, e);
      entity.setProfile("{}");
    }
    entity.setSummaryText(summaryText);
    entity.setRebuiltAt(OffsetDateTime.now());

    tasteProfileRepository.save(entity);
  }

  private double extractAvgSessionMinutes(List<Object[]> sessionStats) {
    if (sessionStats.isEmpty() || sessionStats.getFirst()[1] == null) {
      return 0;
    }
    return ((Number) sessionStats.getFirst()[1]).doubleValue();
  }

  private Map<String, Object> rangeMap(List<Float> values) {
    if (values.isEmpty()) {
      return Map.of();
    }
    Map<String, Object> map = new LinkedHashMap<>();
    map.put("p10", percentile(values, 10));
    map.put("p50", percentile(values, 50));
    map.put("p90", percentile(values, 90));
    map.put("mean", values.stream().mapToDouble(Float::doubleValue).average().orElse(0));
    return map;
  }

  private float percentile(List<Float> values, int p) {
    List<Float> sorted = new ArrayList<>(values);
    Collections.sort(sorted);
    int index = (int) Math.ceil(p / 100.0 * sorted.size()) - 1;
    return sorted.get(Math.max(0, Math.min(index, sorted.size() - 1)));
  }

  private String describeLevel(float value) {
    if (value < 0.33f) return "low";
    if (value < 0.66f) return "moderate";
    return "high";
  }

  private String describeValence(float value) {
    if (value < 0.33f) return "melancholic";
    if (value < 0.66f) return "balanced";
    return "upbeat";
  }

  private record FeatureAggregation(
      List<Float> bpmValues,
      List<Float> energyValues,
      List<Float> valenceValues,
      List<Float> danceabilityValues,
      List<Float> arousalValues) {}
}
