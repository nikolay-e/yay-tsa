package com.yaytsa.server.controller;

import com.yaytsa.server.domain.service.LyricsFetchService;
import com.yaytsa.server.domain.service.LyricsFetchService.OnDemandLyricsResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/Lyrics")
@Tag(name = "Lyrics", description = "On-demand lyrics fetching")
public class LyricsController {

  private static final Logger log = LoggerFactory.getLogger(LyricsController.class);

  private final LyricsFetchService lyricsFetchService;

  public LyricsController(LyricsFetchService lyricsFetchService) {
    this.lyricsFetchService = lyricsFetchService;
  }

  @Operation(
      summary = "Fetch lyrics for a track",
      description =
          "Searches for synced lyrics from multiple sources, saves to disk, and returns the result")
  @ApiResponses(
      value = {
        @ApiResponse(responseCode = "200", description = "Lyrics search completed"),
        @ApiResponse(responseCode = "404", description = "Track not found"),
        @ApiResponse(responseCode = "503", description = "Lyrics service unavailable")
      })
  @PostMapping("/{trackId}/fetch")
  public ResponseEntity<Map<String, Object>> fetchLyrics(
      @Parameter(description = "Audio track ID") @PathVariable UUID trackId) {
    try {
      OnDemandLyricsResult result = lyricsFetchService.fetchLyricsForTrack(trackId);
      return ResponseEntity.ok(
          Map.of(
              "found", result.found(),
              "lyrics", result.lyrics() != null ? result.lyrics() : "",
              "source", result.source() != null ? result.source() : ""));
    } catch (IllegalArgumentException e) {
      return ResponseEntity.status(404).body(Map.of("found", false, "error", "Track not found"));
    } catch (Exception e) {
      log.error("Lyrics fetch failed for track {}: {}", trackId, e.getMessage());
      return ResponseEntity.status(503)
          .body(Map.of("found", false, "error", "Lyrics service unavailable"));
    }
  }
}
