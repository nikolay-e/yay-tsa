package com.yaytsa.server.domain.service;

import com.yaytsa.server.infrastructure.persistence.entity.UserTrackAffinityEntity;
import com.yaytsa.server.infrastructure.persistence.entity.UserTrackAffinityEntity.AffinityId;
import com.yaytsa.server.infrastructure.persistence.repository.ItemRepository;
import com.yaytsa.server.infrastructure.persistence.repository.UserRepository;
import com.yaytsa.server.infrastructure.persistence.repository.UserTrackAffinityRepository;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AffinityAggregationService {

  private static final Logger log = LoggerFactory.getLogger(AffinityAggregationService.class);

  static final double COMPLETION_WEIGHT = 1.0;
  static final double THUMBS_UP_WEIGHT = 2.0;
  static final double PLAY_WEIGHT = 0.3;
  static final double SKIP_EARLY_PENALTY = -1.5;
  static final double THUMBS_DOWN_PENALTY = -3.0;
  private final UserTrackAffinityRepository affinityRepository;
  private final UserRepository userRepository;
  private final ItemRepository itemRepository;

  public AffinityAggregationService(
      UserTrackAffinityRepository affinityRepository,
      UserRepository userRepository,
      ItemRepository itemRepository) {
    this.affinityRepository = affinityRepository;
    this.userRepository = userRepository;
    this.itemRepository = itemRepository;
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void updateAffinityFromSignal(UUID userId, UUID trackId, String signalType) {
    var id = new AffinityId(userId, trackId);
    var affinity =
        affinityRepository.findById(id).orElseGet(() -> createNewAffinity(userId, trackId));

    if (affinity == null) return;

    switch (signalType) {
      case "PLAY_START" -> affinity.setPlayCount(affinity.getPlayCount() + 1);
      case "PLAY_COMPLETE", "SKIP_LATE" ->
          affinity.setCompletionCount(affinity.getCompletionCount() + 1);
      case "SKIP_EARLY", "SKIP_MID" -> affinity.setSkipCount(affinity.getSkipCount() + 1);
      case "THUMBS_UP" -> affinity.setThumbsUpCount(affinity.getThumbsUpCount() + 1);
      case "THUMBS_DOWN" -> affinity.setThumbsDownCount(affinity.getThumbsDownCount() + 1);
      default -> {
        return;
      }
    }

    affinity.setLastSignalAt(OffsetDateTime.now());
    affinity.setAffinityScore(computeAffinity(affinity));
    affinity.setUpdatedAt(OffsetDateTime.now());
    affinityRepository.save(affinity);
  }

  private UserTrackAffinityEntity createNewAffinity(UUID userId, UUID trackId) {
    var entity = new UserTrackAffinityEntity();
    entity.setId(new AffinityId(userId, trackId));
    entity.setUser(userRepository.getReferenceById(userId));
    entity.setTrack(itemRepository.getReferenceById(trackId));
    entity.setUpdatedAt(OffsetDateTime.now());
    return entity;
  }

  private double computeAffinity(UserTrackAffinityEntity a) {
    double implicitScore =
        a.getCompletionCount() * COMPLETION_WEIGHT
            + a.getPlayCount() * PLAY_WEIGHT
            + a.getSkipCount() * SKIP_EARLY_PENALTY;
    double explicitScore =
        a.getThumbsUpCount() * THUMBS_UP_WEIGHT + a.getThumbsDownCount() * THUMBS_DOWN_PENALTY;
    return implicitScore + explicitScore;
  }
}
