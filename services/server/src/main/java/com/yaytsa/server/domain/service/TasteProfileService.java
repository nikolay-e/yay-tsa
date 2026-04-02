package com.yaytsa.server.domain.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yaytsa.server.infrastructure.persistence.entity.TasteProfileEntity;
import com.yaytsa.server.infrastructure.persistence.entity.TrackFeaturesEntity;
import com.yaytsa.server.infrastructure.persistence.repository.PlaybackSignalRepository;
import com.yaytsa.server.infrastructure.persistence.repository.TasteProfileRepository;
import com.yaytsa.server.infrastructure.persistence.repository.TrackFeaturesRepository;
import com.yaytsa.server.infrastructure.persistence.repository.UserRepository;
import java.time.OffsetDateTime;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TasteProfileService {

  private static final Logger log = LoggerFactory.getLogger(TasteProfileService.class);
  private static final int TOP_TRACKS_LIMIT = 200;
  private static final int TOP_ARTISTS_LIMIT = 10;
  private static final int TOP_GENRES_LIMIT = 10;
  private static final int LOOKBACK_DAYS = 90;

  private final TasteProfileRepository tasteProfileRepository;
  private final PlaybackSignalRepository playbackSignalRepository;
  private final TrackFeaturesRepository trackFeaturesRepository;
  private final UserRepository userRepository;
  private final UserEmbeddingService userEmbeddingService;
  private final ObjectMapper objectMapper;

  public TasteProfileService(
      TasteProfileRepository tasteProfileRepository,
      PlaybackSignalRepository playbackSignalRepository,
      TrackFeaturesRepository trackFeaturesRepository,
      UserRepository userRepository,
      UserEmbeddingService userEmbeddingService,
      ObjectMapper objectMapper) {
    this.tasteProfileRepository = tasteProfileRepository;
    this.playbackSignalRepository = playbackSignalRepository;
    this.trackFeaturesRepository = trackFeaturesRepository;
    this.userRepository = userRepository;
    this.userEmbeddingService = userEmbeddingService;
    this.objectMapper = objectMapper;
  }

  @Transactional
  public void rebuildProfile(UUID userId) {
    OffsetDateTime since = OffsetDateTime.now().minusDays(LOOKBACK_DAYS);
    var playCounts = playbackSignalRepository.getPlayCountsByUser(userId, since, TOP_TRACKS_LIMIT);
    if (playCounts.isEmpty()) {
      log.debug("No playback data for user {}, skipping taste profile rebuild", userId);
      return;
    }
    List<UUID> topTrackIds = playCounts.stream().map(row -> (UUID) row[0]).toList();
    Map<UUID, TrackFeaturesEntity> featuresMap = loadFeatures(topTrackIds);
    var agg = aggregateFeatures(playCounts, featuresMap);
    var topArtists = playbackSignalRepository.getTopArtistsByUser(userId, since, TOP_ARTISTS_LIMIT);
    var topGenres = playbackSignalRepository.getTopGenresByUser(userId, since, TOP_GENRES_LIMIT);
    var sessionStats = playbackSignalRepository.getSessionStats(userId, since);
    double avgSessionMin =
        sessionStats.isEmpty() || sessionStats.getFirst()[1] == null
            ? 0
            : ((Number) sessionStats.getFirst()[1]).doubleValue();
    saveProfile(
        userId,
        buildProfileData(agg, topArtists, topGenres, avgSessionMin),
        generateSummary(agg, topArtists, topGenres, avgSessionMin));
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
    int rebuilt = 0;
    for (var user : userRepository.findAll()) {
      try {
        rebuildProfile(user.getId());
        rebuilt++;
      } catch (Exception e) {
        log.warn("Failed to rebuild taste profile for user {}: {}", user.getId(), e.getMessage());
      }
    }
    if (rebuilt > 0) log.info("Rebuilt taste profiles for {} users", rebuilt);
  }

  private Map<UUID, TrackFeaturesEntity> loadFeatures(List<UUID> trackIds) {
    Map<UUID, TrackFeaturesEntity> map = new HashMap<>();
    for (UUID id : trackIds)
      trackFeaturesRepository.findByTrackId(id).ifPresent(f -> map.put(id, f));
    return map;
  }

  private record Agg(
      List<Float> bpm,
      List<Float> energy,
      List<Float> valence,
      List<Float> danceability,
      List<Float> arousal) {}

  private Agg aggregateFeatures(
      List<Object[]> playCounts, Map<UUID, TrackFeaturesEntity> featuresMap) {
    List<Float> bpm = new ArrayList<>(),
        energy = new ArrayList<>(),
        valence = new ArrayList<>(),
        danceability = new ArrayList<>(),
        arousal = new ArrayList<>();
    for (Object[] row : playCounts) {
      TrackFeaturesEntity f = featuresMap.get((UUID) row[0]);
      if (f == null) continue;
      int weight = (int) Math.min(((Number) row[1]).longValue(), 10);
      for (int i = 0; i < weight; i++) {
        if (f.getBpm() != null) bpm.add(f.getBpm());
        if (f.getEnergy() != null) energy.add(f.getEnergy());
        if (f.getValence() != null) valence.add(f.getValence());
        if (f.getDanceability() != null) danceability.add(f.getDanceability());
        if (f.getArousal() != null) arousal.add(f.getArousal());
      }
    }
    return new Agg(bpm, energy, valence, danceability, arousal);
  }

  private Map<String, Object> buildProfileData(
      Agg agg, List<Object[]> topArtists, List<Object[]> topGenres, double avgSessionMin) {
    Map<String, Object> profile = new LinkedHashMap<>();
    profile.put("bpm", rangeMap(agg.bpm));
    profile.put("energy", rangeMap(agg.energy));
    profile.put("valence", rangeMap(agg.valence));
    profile.put("danceability", rangeMap(agg.danceability));
    profile.put("arousal", rangeMap(agg.arousal));
    profile.put(
        "topArtists",
        topArtists.stream()
            .map(
                row -> {
                  Map<String, Object> m = new LinkedHashMap<>();
                  m.put("name", row[0]);
                  m.put("playCount", ((Number) row[1]).longValue());
                  m.put("completions", ((Number) row[2]).longValue());
                  return m;
                })
            .toList());
    profile.put(
        "topGenres",
        topGenres.stream()
            .map(
                row -> {
                  Map<String, Object> m = new LinkedHashMap<>();
                  m.put("name", row[0]);
                  m.put("playCount", ((Number) row[1]).longValue());
                  return m;
                })
            .toList());
    profile.put("avgSessionMinutes", Math.round(avgSessionMin));
    profile.put("lookbackDays", LOOKBACK_DAYS);
    return profile;
  }

  private String generateSummary(
      Agg agg, List<Object[]> topArtists, List<Object[]> topGenres, double avgSessionMin) {
    var sb = new StringBuilder();
    if (!agg.bpm.isEmpty())
      sb.append(
          String.format(
              "Prefers music in the %.0f-%.0f BPM range",
              percentile(agg.bpm, 10), percentile(agg.bpm, 90)));
    if (!agg.energy.isEmpty())
      sb.append(" with ").append(describeLevel(percentile(agg.energy, 50))).append(" energy");
    if (!agg.valence.isEmpty())
      sb.append(" and ").append(describeValence(percentile(agg.valence, 50))).append(" mood");
    sb.append(".");
    if (!topGenres.isEmpty()) {
      var genreJoiner = new StringJoiner(", ");
      topGenres.stream().limit(5).forEach(row -> genreJoiner.add((String) row[0]));
      sb.append(" Top genres: ").append(genreJoiner).append(".");
    }
    if (!topArtists.isEmpty()) {
      var joiner = new StringJoiner(", ");
      topArtists.stream().limit(5).forEach(row -> joiner.add((String) row[0]));
      sb.append(" Top artists: ").append(joiner).append(".");
    }
    if (avgSessionMin > 0)
      sb.append(String.format(" Average session length: %.0fmin.", avgSessionMin));
    return sb.toString();
  }

  private void saveProfile(UUID userId, Map<String, Object> profileData, String summaryText) {
    var entity = tasteProfileRepository.findById(userId).orElseGet(TasteProfileEntity::new);
    if (entity.getUserId() == null)
      entity.setUser(
          userRepository
              .findById(userId)
              .orElseThrow(() -> new IllegalArgumentException("User not found: " + userId)));
    try {
      entity.setProfile(objectMapper.writeValueAsString(profileData));
    } catch (JsonProcessingException e) {
      log.error("Failed to serialize taste profile for user {}", userId, e);
      entity.setProfile("{}");
    }
    entity.setSummaryText(summaryText);
    try {
      var embeddings = userEmbeddingService.computeUserEmbeddings(userId);
      if (embeddings != null) {
        entity.setEmbeddingMert(embeddings.mert());
        entity.setEmbeddingClap(embeddings.clap());
        entity.setTrackCount(embeddings.trackCount());
      }
    } catch (Exception e) {
      log.warn("Failed to compute user embeddings for user {}: {}", userId, e.getMessage());
    }
    entity.setRebuiltAt(OffsetDateTime.now());
    tasteProfileRepository.save(entity);
  }

  private Map<String, Object> rangeMap(List<Float> values) {
    if (values.isEmpty()) return Map.of();
    Map<String, Object> map = new LinkedHashMap<>();
    map.put("p10", percentile(values, 10));
    map.put("p50", percentile(values, 50));
    map.put("p90", percentile(values, 90));
    map.put("mean", values.stream().mapToDouble(Float::doubleValue).average().orElse(0));
    return map;
  }

  private float percentile(List<Float> values, int p) {
    var sorted = new ArrayList<>(values);
    Collections.sort(sorted);
    int index = (int) Math.ceil(p / 100.0 * sorted.size()) - 1;
    return sorted.get(Math.max(0, Math.min(index, sorted.size() - 1)));
  }

  private String describeLevel(float value) {
    if (value < 0.33f) return "low";
    return value < 0.66f ? "moderate" : "high";
  }

  private String describeValence(float value) {
    if (value < 0.33f) return "melancholic";
    return value < 0.66f ? "balanced" : "upbeat";
  }
}
