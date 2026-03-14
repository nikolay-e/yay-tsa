package com.yaytsa.server.controller;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.yaytsa.server.domain.service.ImageService;
import com.yaytsa.server.domain.service.LibraryScanService;
import com.yaytsa.server.domain.service.UserManagementService;
import com.yaytsa.server.domain.service.UserService;
import com.yaytsa.server.infrastructure.fs.MediaScannerTransactionalService;
import com.yaytsa.server.infrastructure.persistence.entity.UserEntity;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/Admin")
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Admin", description = "Administrative operations (requires admin role)")
public class AdminController {

  private final ImageService imageService;
  private final MediaScannerTransactionalService mediaScannerService;
  private final LibraryScanService libraryScanService;
  private final UserManagementService userManagementService;
  private final UserService userService;

  public AdminController(
      ImageService imageService,
      MediaScannerTransactionalService mediaScannerService,
      LibraryScanService libraryScanService,
      UserManagementService userManagementService,
      UserService userService) {
    this.imageService = imageService;
    this.mediaScannerService = mediaScannerService;
    this.libraryScanService = libraryScanService;
    this.userManagementService = userManagementService;
    this.userService = userService;
  }

  record UserSummary(
      @JsonProperty("Id") String id,
      @JsonProperty("Username") String username,
      @JsonProperty("DisplayName") String displayName,
      @JsonProperty("IsAdmin") boolean isAdmin,
      @JsonProperty("IsActive") boolean isActive,
      @JsonProperty("CreatedAt") OffsetDateTime createdAt,
      @JsonProperty("LastLoginAt") OffsetDateTime lastLoginAt) {

    static UserSummary from(UserEntity e) {
      return new UserSummary(
          e.getId().toString(),
          e.getUsername(),
          e.getDisplayName(),
          e.isAdmin(),
          e.isActive(),
          e.getCreatedAt(),
          e.getLastLoginAt());
    }
  }

  record CreateUserRequest(
      @JsonProperty("Username") String username,
      @JsonProperty("DisplayName") String displayName,
      @JsonProperty("IsAdmin") boolean isAdmin) {}

  @GetMapping("/Users")
  public ResponseEntity<List<UserSummary>> listUsers() {
    List<UserSummary> users =
        userManagementService.listAll().stream().map(UserSummary::from).toList();
    return ResponseEntity.ok(users);
  }

  @PostMapping("/Users")
  public ResponseEntity<Map<String, Object>> createUser(@RequestBody CreateUserRequest request) {
    var created =
        userManagementService.createUser(
            request.username(), request.displayName(), request.isAdmin());
    return ResponseEntity.ok(
        Map.of(
            "user", UserSummary.from(created.user()),
            "initialPassword", created.plainPassword()));
  }

  @DeleteMapping("/Users/{userId}")
  public ResponseEntity<Void> deleteUser(@PathVariable UUID userId, Authentication authentication) {
    UUID currentUserId = resolveCurrentUserId(authentication);
    userManagementService.deleteUser(userId, currentUserId);
    return ResponseEntity.noContent().build();
  }

  @PostMapping("/Users/{userId}/ResetPassword")
  public ResponseEntity<Map<String, Object>> resetPassword(
      @PathVariable UUID userId, Authentication authentication) {
    UUID currentUserId = resolveCurrentUserId(authentication);
    String newPassword = userManagementService.resetPassword(userId, currentUserId);
    return ResponseEntity.ok(Map.of("newPassword", newPassword));
  }

  private UUID resolveCurrentUserId(Authentication authentication) {
    return userService.getCurrentUser(authentication).getId();
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
}
