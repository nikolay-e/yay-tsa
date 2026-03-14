package com.yaytsa.server.domain.service;

import com.yaytsa.server.infrastructure.persistence.entity.TrackFeaturesEntity;
import com.yaytsa.server.infrastructure.persistence.entity.UserTrackAffinityEntity;
import com.yaytsa.server.infrastructure.persistence.repository.TrackFeaturesRepository;
import com.yaytsa.server.infrastructure.persistence.repository.UserTrackAffinityRepository;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

@Service
public class UserEmbeddingService {

  private static final Logger log = LoggerFactory.getLogger(UserEmbeddingService.class);
  private static final int TOP_TRACKS_FOR_EMBEDDING = 200;

  private final UserTrackAffinityRepository affinityRepository;
  private final TrackFeaturesRepository featuresRepository;

  public UserEmbeddingService(
      UserTrackAffinityRepository affinityRepository, TrackFeaturesRepository featuresRepository) {
    this.affinityRepository = affinityRepository;
    this.featuresRepository = featuresRepository;
  }

  public record UserEmbeddings(float[] mert, float[] clap, int trackCount) {}

  public UserEmbeddings computeUserEmbeddings(UUID userId) {
    List<UserTrackAffinityEntity> positiveAffinities =
        affinityRepository.findTopPositiveByUserId(
            userId, PageRequest.of(0, TOP_TRACKS_FOR_EMBEDDING));

    if (positiveAffinities.isEmpty()) {
      log.debug("No positive affinities for user {}, skipping embedding computation", userId);
      return null;
    }

    List<UUID> trackIds = positiveAffinities.stream().map(a -> a.getId().getTrackId()).toList();

    List<TrackFeaturesEntity> features =
        featuresRepository.findAllById(trackIds).stream()
            .filter(f -> f.getEmbeddingMert() != null || f.getEmbeddingClap() != null)
            .toList();

    if (features.isEmpty()) {
      log.debug("No tracks with embeddings for user {}", userId);
      return null;
    }

    java.util.Map<UUID, TrackFeaturesEntity> featureMap = new java.util.HashMap<>();
    for (var f : features) featureMap.put(f.getTrackId(), f);

    float[] mertCentroid = null;
    float[] clapCentroid = null;
    double mertWeightSum = 0;
    double clapWeightSum = 0;
    int trackCount = 0;

    for (var affinity : positiveAffinities) {
      var feat = featureMap.get(affinity.getId().getTrackId());
      if (feat == null) continue;

      double weight = affinity.getAffinityScore();
      trackCount++;

      if (feat.getEmbeddingMert() != null) {
        if (mertCentroid == null) mertCentroid = new float[feat.getEmbeddingMert().length];
        float[] emb = feat.getEmbeddingMert();
        for (int i = 0; i < emb.length; i++) mertCentroid[i] += (float) (emb[i] * weight);
        mertWeightSum += weight;
      }

      if (feat.getEmbeddingClap() != null) {
        if (clapCentroid == null) clapCentroid = new float[feat.getEmbeddingClap().length];
        float[] emb = feat.getEmbeddingClap();
        for (int i = 0; i < emb.length; i++) clapCentroid[i] += (float) (emb[i] * weight);
        clapWeightSum += weight;
      }
    }

    if (mertCentroid != null && mertWeightSum > 0) {
      for (int i = 0; i < mertCentroid.length; i++) mertCentroid[i] /= (float) mertWeightSum;
      l2Normalize(mertCentroid);
    }

    if (clapCentroid != null && clapWeightSum > 0) {
      for (int i = 0; i < clapCentroid.length; i++) clapCentroid[i] /= (float) clapWeightSum;
      l2Normalize(clapCentroid);
    }

    log.info(
        "Computed user embeddings for user {}: {} tracks, mert={}, clap={}",
        userId,
        trackCount,
        mertCentroid != null,
        clapCentroid != null);

    return new UserEmbeddings(mertCentroid, clapCentroid, trackCount);
  }

  private static void l2Normalize(float[] vec) {
    double norm = 0;
    for (float v : vec) norm += v * v;
    norm = Math.sqrt(norm);
    if (norm > 1e-9) {
      for (int i = 0; i < vec.length; i++) vec[i] /= (float) norm;
    }
  }
}
