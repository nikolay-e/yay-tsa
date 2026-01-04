package com.yaytsa.server.controller;

import com.yaytsa.server.domain.service.ImageService;
import com.yaytsa.server.infrastructure.fs.MediaScannerTransactionalService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
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

  public AdminController(
      ImageService imageService, MediaScannerTransactionalService mediaScannerService) {
    this.imageService = imageService;
    this.mediaScannerService = mediaScannerService;
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
}
