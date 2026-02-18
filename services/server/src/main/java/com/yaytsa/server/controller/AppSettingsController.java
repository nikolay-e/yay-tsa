package com.yaytsa.server.controller;

import com.yaytsa.server.domain.service.AppSettingsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/Admin/Settings")
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Settings", description = "Application settings (admin only)")
public class AppSettingsController {

  private static final String[] SECRET_KEYS = {
    "metadata.genius.token",
    "metadata.lastfm.api-key",
    "metadata.spotify.client-id",
    "metadata.spotify.client-secret"
  };

  private static final String[] SERVICE_URL_KEYS = {
    "service.separator-url",
    "service.lyrics-url"
  };

  private final AppSettingsService settingsService;

  public AppSettingsController(AppSettingsService settingsService) {
    this.settingsService = settingsService;
  }

  @Operation(summary = "Get metadata provider settings")
  @GetMapping("/metadata")
  public ResponseEntity<Map<String, String>> getMetadataSettings() {
    return ResponseEntity.ok(
        Map.of(
            "metadata.genius.token",
                mask(settingsService.get("metadata.genius.token", "GENIUS_ACCESS_TOKEN")),
            "metadata.lastfm.api-key",
                mask(settingsService.get("metadata.lastfm.api-key", "LASTFM_API_KEY")),
            "metadata.spotify.client-id",
                mask(settingsService.get("metadata.spotify.client-id", "SPOTIFY_CLIENT_ID")),
            "metadata.spotify.client-secret",
                mask(
                    settingsService.get(
                        "metadata.spotify.client-secret", "SPOTIFY_CLIENT_SECRET"))));
  }

  @Operation(summary = "Update metadata provider settings")
  @PutMapping("/metadata")
  public ResponseEntity<Map<String, String>> updateMetadataSettings(
      @RequestBody Map<String, String> settings) {
    for (String key : SECRET_KEYS) {
      String value = settings.get(key);
      if (value != null && !value.isBlank()) {
        settingsService.set(key, value);
      }
    }
    return ResponseEntity.ok(Map.of("status", "saved"));
  }

  @Operation(summary = "Get service URL settings")
  @GetMapping("/services")
  public ResponseEntity<Map<String, String>> getServiceSettings() {
    return ResponseEntity.ok(
        Map.of(
            "service.separator-url",
                settingsService.get("service.separator-url", "AUDIO_SEPARATOR_URL"),
            "service.lyrics-url",
                settingsService.get("service.lyrics-url", "LYRICS_FETCHER_URL")));
  }

  @Operation(summary = "Update service URL settings")
  @PutMapping("/services")
  public ResponseEntity<Map<String, String>> updateServiceSettings(
      @RequestBody Map<String, String> settings) {
    for (String key : SERVICE_URL_KEYS) {
      String value = settings.get(key);
      if (value != null && !value.isBlank()) {
        settingsService.set(key, value);
      }
    }
    return ResponseEntity.ok(Map.of("status", "saved"));
  }

  private String mask(String value) {
    if (value == null || value.isBlank()) return "";
    if (value.length() <= 8) return "****";
    return value.substring(0, 4) + "****" + value.substring(value.length() - 4);
  }
}
