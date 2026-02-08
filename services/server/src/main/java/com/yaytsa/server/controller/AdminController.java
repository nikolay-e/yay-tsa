package com.yaytsa.server.controller;

import com.yaytsa.server.domain.service.ImageService;
import com.yaytsa.server.domain.service.LibraryScanService;
import com.yaytsa.server.domain.service.LyricsFetchService;
import com.yaytsa.server.domain.service.LyricsFetchService.FetchStats;
import com.yaytsa.server.infrastructure.fs.MediaScannerTransactionalService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/Admin")
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Admin", description = "Administrative operations (requires admin role)")
public class AdminController {

  private final ImageService imageService;
  private final MediaScannerTransactionalService mediaScannerService;
  private final LibraryScanService libraryScanService;
  private final LyricsFetchService lyricsFetchService;

  public AdminController(
      ImageService imageService,
      MediaScannerTransactionalService mediaScannerService,
      LibraryScanService libraryScanService,
      LyricsFetchService lyricsFetchService) {
    this.imageService = imageService;
    this.mediaScannerService = mediaScannerService;
    this.libraryScanService = libraryScanService;
    this.lyricsFetchService = lyricsFetchService;
  }

  @Operation(
      summary = "Get cache statistics",
      description = "Returns current cache statistics including size and hit rates")
  @ApiResponse(responseCode = "200", description = "Cache statistics retrieved")
  @GetMapping("/Cache/Stats")
  public ResponseEntity<Map<String, Object>> getCacheStats() {
    var cache = imageService.getCache();
    var stats = cache.stats();

    return ResponseEntity.ok(
        Map.of(
            "imageCache",
            Map.of(
                "size", cache.estimatedSize(),
                "hitCount", stats.hitCount(),
                "missCount", stats.missCount(),
                "hitRate", stats.hitRate(),
                "evictionCount", stats.evictionCount(),
                "loadSuccessCount", stats.loadSuccessCount(),
                "loadFailureCount", stats.loadFailureCount(),
                "averageLoadPenaltyMs", stats.averageLoadPenalty() / 1_000_000.0)));
  }

  @Operation(
      summary = "Clear all caches",
      description = "Invalidates all cached data including images. Forces reload from disk.")
  @ApiResponse(responseCode = "200", description = "Caches cleared successfully")
  @DeleteMapping("/Cache")
  public ResponseEntity<Map<String, Object>> clearAllCaches() {
    var cache = imageService.getCache();
    long sizeBefore = cache.estimatedSize();
    cache.invalidateAll();
    cache.cleanUp();

    return ResponseEntity.ok(
        Map.of(
            "cleared",
            true,
            "entriesCleared",
            sizeBefore,
            "message",
            "All caches invalidated. Images will be reloaded from disk on next request."));
  }

  @Operation(
      summary = "Clear image cache for specific item",
      description = "Invalidates cached images for a specific item ID")
  @ApiResponse(responseCode = "200", description = "Item cache cleared")
  @DeleteMapping("/Cache/Images/{itemId}")
  public ResponseEntity<Map<String, Object>> clearItemCache(@PathVariable String itemId) {
    var cache = imageService.getCache();
    String prefix = itemId + "-";
    // O(n) scan - Caffeine doesn't support prefix-based invalidation natively
    var keysToInvalidate =
        cache.asMap().keySet().stream().filter(key -> key.startsWith(prefix)).toList();
    cache.invalidateAll(keysToInvalidate);
    cache.cleanUp();

    return ResponseEntity.ok(
        Map.of("cleared", true, "itemId", itemId, "entriesCleared", keysToInvalidate.size()));
  }

  @Operation(
      summary = "Scan for missing artwork",
      description =
          "Scans all albums and artists without Primary images and attempts to find "
              + "artwork files (cover.jpg, folder.jpg, etc.) on disk")
  @ApiResponse(responseCode = "200", description = "Artwork scan completed")
  @PostMapping("/Library/ScanMissingArtwork")
  public ResponseEntity<Map<String, Object>> scanMissingArtwork() {
    int found = mediaScannerService.scanMissingArtwork();
    return ResponseEntity.ok(
        Map.of("success", true, "artworkFound", found, "message", "Missing artwork scan complete"));
  }

  @Operation(
      summary = "Rescan media library",
      description =
          "Triggers a full rescan of the media library from disk. "
              + "Discovers new files, updates changed files, and removes deleted files.")
  @ApiResponse(responseCode = "200", description = "Library rescan initiated")
  @ApiResponse(responseCode = "409", description = "Scan already in progress")
  @PostMapping("/Library/Rescan")
  public ResponseEntity<Map<String, Object>> rescanLibrary() {
    var scanFuture = libraryScanService.triggerFullScan();
    if (scanFuture.isPresent()) {
      return ResponseEntity.ok(
          Map.of("success", true, "message", "Library rescan initiated", "scanInProgress", true));
    }
    return ResponseEntity.status(409)
        .body(Map.of("success", false, "message", "Scan already in progress"));
  }

  @Operation(
      summary = "Get library scan status",
      description = "Returns whether a scan is currently in progress")
  @ApiResponse(responseCode = "200", description = "Scan status retrieved")
  @GetMapping("/Library/ScanStatus")
  public ResponseEntity<Map<String, Object>> getScanStatus() {
    return ResponseEntity.ok(Map.of("scanInProgress", libraryScanService.isScanInProgress()));
  }

  @Operation(
      summary = "Fetch missing lyrics",
      description =
          "Triggers fetching of synced lyrics (.lrc) for all tracks missing them. "
              + "Uses the audio-separator sidecar's syncedlyrics integration.")
  @ApiResponse(responseCode = "200", description = "Lyrics fetch initiated")
  @ApiResponse(responseCode = "409", description = "Lyrics fetch already in progress")
  @PostMapping("/Library/FetchLyrics")
  public ResponseEntity<Map<String, Object>> fetchLyrics() {
    if (lyricsFetchService.isFetchInProgress()) {
      return ResponseEntity.status(409)
          .body(Map.of("success", false, "message", "Lyrics fetch already in progress"));
    }
    new Thread(() -> lyricsFetchService.fetchMissingLyrics(), "lyrics-fetch-manual").start();
    return ResponseEntity.ok(
        Map.of("success", true, "message", "Lyrics fetch initiated", "fetchInProgress", true));
  }

  @Operation(
      summary = "Refetch broken lyrics",
      description =
          "Re-fetches lyrics for tracks with broken, empty, or negative-cached .lrc files. "
              + "Validates existing files and force-refetches from multiple sources when needed.")
  @ApiResponse(responseCode = "200", description = "Lyrics refetch initiated")
  @ApiResponse(responseCode = "409", description = "Lyrics fetch already in progress")
  @PostMapping("/Library/RefetchLyrics")
  public ResponseEntity<Map<String, Object>> refetchLyrics() {
    if (lyricsFetchService.isFetchInProgress()) {
      return ResponseEntity.status(409)
          .body(Map.of("success", false, "message", "Lyrics fetch already in progress"));
    }
    new Thread(() -> lyricsFetchService.refetchBrokenLyrics(), "lyrics-refetch").start();
    return ResponseEntity.ok(
        Map.of(
            "success",
            true,
            "message",
            "Lyrics refetch initiated for broken/missing files",
            "fetchInProgress",
            true));
  }

  @Operation(
      summary = "Get lyrics fetch status",
      description = "Returns whether a lyrics fetch is running and stats from the last run")
  @ApiResponse(responseCode = "200", description = "Lyrics fetch status retrieved")
  @GetMapping("/Library/LyricsFetchStatus")
  public ResponseEntity<Map<String, Object>> getLyricsFetchStatus() {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("fetchInProgress", lyricsFetchService.isFetchInProgress());

    FetchStats stats = lyricsFetchService.getLastRunStats();
    if (stats != null) {
      Map<String, Object> lastRun = new LinkedHashMap<>();
      lastRun.put("total", stats.total());
      lastRun.put("fetched", stats.fetched());
      lastRun.put("skipped", stats.skipped());
      lastRun.put("failed", stats.failed());
      lastRun.put("startedAt", stats.startedAt().toString());
      if (stats.completedAt() != null) {
        lastRun.put("completedAt", stats.completedAt().toString());
      }
      result.put("lastRun", lastRun);
    }

    return ResponseEntity.ok(result);
  }
}
