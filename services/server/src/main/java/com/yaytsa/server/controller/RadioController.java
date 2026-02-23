package com.yaytsa.server.controller;

import com.yaytsa.server.domain.service.PlayStateService;
import com.yaytsa.server.domain.service.radio.SmartRadioService;
import com.yaytsa.server.domain.service.radio.TrackAnalysisService;
import com.yaytsa.server.dto.response.BaseItemResponse;
import com.yaytsa.server.dto.response.QueryResultResponse;
import com.yaytsa.server.dto.response.RadioFiltersResponse;
import com.yaytsa.server.infrastructure.persistence.entity.AudioTrackEntity;
import com.yaytsa.server.infrastructure.persistence.entity.PlayStateEntity;
import com.yaytsa.server.infrastructure.persistence.repository.AudioTrackRepository;
import com.yaytsa.server.infrastructure.persistence.repository.TrackAudioFeaturesRepository;
import com.yaytsa.server.infrastructure.security.AuthenticatedUser;
import com.yaytsa.server.mapper.ItemMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.*;
import java.util.stream.Collectors;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@Tag(name = "Radio", description = "Smart Radio / My Wave")
public class RadioController {

  private final TrackAnalysisService analysisService;
  private final SmartRadioService smartRadioService;
  private final TrackAudioFeaturesRepository featuresRepository;
  private final AudioTrackRepository audioTrackRepository;
  private final ItemMapper itemMapper;
  private final PlayStateService playStateService;

  public RadioController(
      TrackAnalysisService analysisService,
      SmartRadioService smartRadioService,
      TrackAudioFeaturesRepository featuresRepository,
      AudioTrackRepository audioTrackRepository,
      ItemMapper itemMapper,
      PlayStateService playStateService) {
    this.analysisService = analysisService;
    this.smartRadioService = smartRadioService;
    this.featuresRepository = featuresRepository;
    this.audioTrackRepository = audioTrackRepository;
    this.itemMapper = itemMapper;
    this.playStateService = playStateService;
  }

  @Operation(summary = "Get recommended tracks for My Wave radio")
  @GetMapping("/Radio/MyWave")
  public ResponseEntity<QueryResultResponse<BaseItemResponse>> getMyWave(
      @AuthenticationPrincipal AuthenticatedUser user,
      @RequestParam(required = false) String mood,
      @RequestParam(required = false) String language,
      @RequestParam(required = false) Short minEnergy,
      @RequestParam(required = false) Short maxEnergy,
      @RequestParam(defaultValue = "20") int count) {

    count = Math.max(1, Math.min(count, 100));
    // Normalize and validate filter params
    if (mood != null) {
      mood = mood.length() > 50 ? mood.substring(0, 50) : mood;
      mood = mood.toLowerCase();
    }
    if (language != null) {
      language = language.length() > 10 ? language.substring(0, 10) : language;
      language = language.toLowerCase();
    }
    UUID userId = user.getUserEntity().getId();
    List<UUID> trackIds =
        smartRadioService.generateRadio(userId, mood, language, minEnergy, maxEnergy, count);

    if (trackIds.isEmpty()) {
      return ResponseEntity.ok(new QueryResultResponse<>(List.of(), 0, 0));
    }

    List<AudioTrackEntity> tracks = audioTrackRepository.findAllByIdInWithRelations(trackIds);

    // Preserve order from recommendation algorithm
    Map<UUID, AudioTrackEntity> trackMap =
        tracks.stream().collect(Collectors.toMap(AudioTrackEntity::getItemId, t -> t));

    Map<UUID, PlayStateEntity> playStates = playStateService.getPlayStatesForItems(userId, trackIds);

    List<BaseItemResponse> dtos =
        trackIds.stream()
            .map(trackMap::get)
            .filter(Objects::nonNull)
            .map(
                track ->
                    itemMapper.toDto(
                        track.getItem(),
                        playStates.get(track.getItemId()),
                        track,
                        null))
            .collect(Collectors.toList());

    return ResponseEntity.ok(new QueryResultResponse<>(dtos, dtos.size(), 0));
  }

  @Operation(summary = "Get available radio filters (moods, languages)")
  @GetMapping("/Radio/MyWave/Filters")
  public ResponseEntity<RadioFiltersResponse> getFilters(
      @AuthenticationPrincipal AuthenticatedUser user) {
    return ResponseEntity.ok(
        new RadioFiltersResponse(
            featuresRepository.findDistinctMoods(),
            featuresRepository.findDistinctLanguages(),
            featuresRepository.countTotalTracks(),
            featuresRepository.countAnalyzed()));
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
