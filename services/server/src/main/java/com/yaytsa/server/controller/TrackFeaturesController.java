package com.yaytsa.server.controller;

import com.yaytsa.server.dto.response.TrackFeaturesResponse;
import com.yaytsa.server.error.ResourceNotFoundException;
import com.yaytsa.server.error.ResourceType;
import com.yaytsa.server.infrastructure.persistence.entity.TrackFeaturesEntity;
import com.yaytsa.server.infrastructure.persistence.repository.TrackFeaturesRepository;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/v1/tracks")
@Transactional(readOnly = true)
public class TrackFeaturesController {

  private final TrackFeaturesRepository featuresRepository;

  public TrackFeaturesController(TrackFeaturesRepository featuresRepository) {
    this.featuresRepository = featuresRepository;
  }

  @GetMapping("/{trackId}/features")
  public ResponseEntity<TrackFeaturesResponse> getFeatures(@PathVariable UUID trackId) {

    TrackFeaturesEntity entity =
        featuresRepository
            .findByTrackId(trackId)
            .orElseThrow(() -> new ResourceNotFoundException(ResourceType.TrackFeatures, trackId));

    return ResponseEntity.ok(toResponse(entity));
  }

  private TrackFeaturesResponse toResponse(TrackFeaturesEntity entity) {
    return new TrackFeaturesResponse(
        entity.getTrackId().toString(),
        entity.getBpm(),
        entity.getBpmConfidence(),
        entity.getMusicalKey(),
        entity.getKeyConfidence(),
        entity.getEnergy(),
        entity.getLoudnessIntegrated(),
        entity.getLoudnessRange(),
        entity.getAverageLoudness(),
        entity.getValence(),
        entity.getArousal(),
        entity.getDanceability(),
        entity.getVocalInstrumental(),
        entity.getSpectralComplexity(),
        entity.getDissonance(),
        entity.getOnsetRate(),
        entity.getExtractedAt(),
        entity.getExtractorVersion());
  }
}
