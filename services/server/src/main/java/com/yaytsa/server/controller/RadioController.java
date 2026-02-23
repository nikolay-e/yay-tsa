package com.yaytsa.server.controller;

import com.yaytsa.server.domain.service.AppSettingsService;
import com.yaytsa.server.domain.service.radio.TrackAnalysisService;
import com.yaytsa.server.dto.response.RadioFiltersResponse;
import com.yaytsa.server.dto.response.RadioResponse;
import com.yaytsa.server.infrastructure.persistence.repository.TrackAudioFeaturesRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@Tag(name = "Radio", description = "Smart Radio / My Wave")
public class RadioController {

  private final TrackAnalysisService analysisService;
  private final TrackAudioFeaturesRepository featuresRepository;

  public RadioController(
      TrackAnalysisService analysisService,
      TrackAudioFeaturesRepository featuresRepository) {
    this.analysisService = analysisService;
    this.featuresRepository = featuresRepository;
  }

  @Operation(summary = "Get available radio filters (moods, languages)")
  @GetMapping("/Radio/MyWave/Filters")
  public ResponseEntity<RadioFiltersResponse> getFilters() {
    return ResponseEntity.ok(
        new RadioFiltersResponse(
            featuresRepository.findDistinctMoods(), featuresRepository.findDistinctLanguages()));
  }

  @Operation(summary = "Start batch analysis of unanalyzed tracks")
  @PostMapping("/Radio/Analysis/Start")
  @PreAuthorize("hasRole('ADMIN')")
  public ResponseEntity<Map<String, String>> startBatchAnalysis() {
    analysisService.startBatchAnalysis();
    return ResponseEntity.ok(Map.of("status", "started"));
  }

  @Operation(summary = "Stop batch analysis")
  @PostMapping("/Radio/Analysis/Stop")
  @PreAuthorize("hasRole('ADMIN')")
  public ResponseEntity<Map<String, String>> stopBatchAnalysis() {
    analysisService.stopBatchAnalysis();
    return ResponseEntity.ok(Map.of("status", "stopped"));
  }

  @Operation(summary = "Get analysis progress stats")
  @GetMapping("/Radio/Analysis/Stats")
  @PreAuthorize("hasRole('ADMIN')")
  public ResponseEntity<TrackAnalysisService.AnalysisStats> getAnalysisStats() {
    return ResponseEntity.ok(analysisService.getStats());
  }

  @Operation(summary = "Analyze a single track")
  @PostMapping("/Radio/Analysis/Track/{id}")
  @PreAuthorize("hasRole('ADMIN')")
  public ResponseEntity<Map<String, Object>> analyzeTrack(@PathVariable UUID id) {
    boolean success = analysisService.analyzeTrack(id);
    return ResponseEntity.ok(Map.of("success", success, "itemId", id.toString()));
  }
}
