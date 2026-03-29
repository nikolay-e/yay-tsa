package com.yaytsa.server.domain.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.yaytsa.server.infrastructure.persistence.entity.ListeningSessionEntity;
import com.yaytsa.server.infrastructure.persistence.repository.PlaybackSignalRepository;
import com.yaytsa.server.infrastructure.persistence.repository.TrackFeaturesRepository;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class RadioAnchorResolver {

  private static final Logger log = LoggerFactory.getLogger(RadioAnchorResolver.class);

  private static final float MIN_WEIGHT = 0.40f;
  private static final float INITIAL_WEIGHT = 0.7f;
  private static final float COMPLETED_DECAY = 0.006f;
  private static final float SKIP_LATE_DECAY = 0.009f;
  private static final float SKIPPED_DECAY = 0.012f;
  private static final float THUMBS_DOWN_DECAY = 0.02f;
  private static final float THUMBS_UP_DECAY = 0.002f;

  private final TrackFeaturesRepository trackFeaturesRepository;
  private final PlaybackSignalRepository signalRepository;

  private final Cache<UUID, float[]> embeddingCache =
      Caffeine.newBuilder().maximumSize(200).expireAfterWrite(Duration.ofHours(1)).build();

  public RadioAnchorResolver(
      TrackFeaturesRepository trackFeaturesRepository, PlaybackSignalRepository signalRepository) {
    this.trackFeaturesRepository = trackFeaturesRepository;
    this.signalRepository = signalRepository;
  }

  public float[] resolveAnchorEmbedding(ListeningSessionEntity session) {
    UUID seedTrackId = session.getSeedTrackId();
    if (seedTrackId == null) return null;

    return embeddingCache.get(
        seedTrackId,
        id ->
            trackFeaturesRepository
                .findByTrackId(id)
                .map(
                    tf -> {
                      if (tf.getEmbeddingMert() != null) return tf.getEmbeddingMert();
                      log.debug(
                          "No MERT embedding for seed track {}, anchor unavailable", seedTrackId);
                      return null;
                    })
                .orElse(null));
  }

  public float computeWeight(UUID sessionId) {
    List<Object[]> signalCounts = signalRepository.countSignalsByTypeForSession(sessionId);

    float decay = 0;
    for (Object[] row : signalCounts) {
      String signalType = (String) row[0];
      long count = ((Number) row[1]).longValue();
      switch (signalType) {
        case "PLAY_COMPLETE" -> decay += count * COMPLETED_DECAY;
        case "SKIP_LATE" -> decay += count * SKIP_LATE_DECAY;
        case "SKIP_EARLY", "SKIP_MID" -> decay += count * SKIPPED_DECAY;
        case "THUMBS_DOWN" -> decay += count * THUMBS_DOWN_DECAY;
        case "THUMBS_UP" -> decay += count * THUMBS_UP_DECAY;
      }
    }

    float weight = Math.max(MIN_WEIGHT, INITIAL_WEIGHT - decay);
    log.debug("Radio anchor weight for session {}: {} (decay: {})", sessionId, weight, decay);
    return weight;
  }
}
