package com.yaytsa.server.domain.service;

import com.yaytsa.server.error.ResourceNotFoundException;
import com.yaytsa.server.error.ResourceType;
import com.yaytsa.server.infrastructure.persistence.entity.TrackFeaturesEntity;
import com.yaytsa.server.infrastructure.persistence.repository.TrackFeaturesRepository;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class TrackFeaturesService {

  private final TrackFeaturesRepository featuresRepository;

  public TrackFeaturesService(TrackFeaturesRepository featuresRepository) {
    this.featuresRepository = featuresRepository;
  }

  public TrackFeaturesEntity getFeatures(UUID trackId) {
    return featuresRepository
        .findByTrackId(trackId)
        .orElseThrow(() -> new ResourceNotFoundException(ResourceType.TrackFeatures, trackId));
  }
}
