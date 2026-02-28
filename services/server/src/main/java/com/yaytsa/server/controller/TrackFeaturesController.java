package com.yaytsa.server.controller;

import com.yaytsa.server.domain.service.TrackFeaturesService;
import com.yaytsa.server.dto.response.TrackFeaturesResponse;
import com.yaytsa.server.infrastructure.persistence.entity.TrackFeaturesEntity;
import com.yaytsa.server.infrastructure.security.AuthenticatedUser;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/v1/tracks")
public class TrackFeaturesController {

  private final TrackFeaturesService featuresService;

  public TrackFeaturesController(TrackFeaturesService featuresService) {
    this.featuresService = featuresService;
  }

  @GetMapping("/{trackId}/features")
  public ResponseEntity<TrackFeaturesResponse> getFeatures(
      @PathVariable UUID trackId, @AuthenticationPrincipal AuthenticatedUser authenticatedUser) {

    TrackFeaturesEntity entity = featuresService.getFeatures(trackId);

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
