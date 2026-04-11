package com.yaytsa.server.controller;

import com.yaytsa.server.domain.service.AppSettingsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.net.URI;
import java.net.URISyntaxException;
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
    "metadata.spotify.client-secret",
    "metadata.acoustid.api-key"
  };

  private static final String[] SERVICE_URL_KEYS = {"service.separator-url", "service.lyrics-url"};

  private final AppSettingsService settingsService;

  public AppSettingsController(AppSettingsService settingsService) {
    this.settingsService = settingsService;
  }

  @Operation(summary = "Get metadata provider settings")
  @ApiResponses(
      value = {
        @ApiResponse(responseCode = "200", description = "Settings returned"),
        @ApiResponse(responseCode = "401", description = "Unauthorized"),
        @ApiResponse(responseCode = "403", description = "Forbidden")
      })
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
                    settingsService.get("metadata.spotify.client-secret", "SPOTIFY_CLIENT_SECRET")),
            "metadata.acoustid.api-key",
                mask(settingsService.get("metadata.acoustid.api-key", "ACOUSTID_API_KEY"))));
  }

  @Operation(summary = "Update metadata provider settings")
  @ApiResponses(
      value = {
        @ApiResponse(responseCode = "200", description = "Settings updated"),
        @ApiResponse(responseCode = "400", description = "Invalid settings"),
        @ApiResponse(responseCode = "401", description = "Unauthorized"),
        @ApiResponse(responseCode = "403", description = "Forbidden")
      })
  @PutMapping("/metadata")
  public ResponseEntity<Map<String, String>> updateMetadataSettings(
      @RequestBody Map<String, String> settings) {
    for (String key : SECRET_KEYS) {
      String value = settings.get(key);
      if (value != null && !value.isBlank()) {
        if (value.length() > MAX_SETTING_VALUE_LENGTH) {
          return ResponseEntity.badRequest()
              .body(Map.of("error", "Value too long for key: " + key));
        }
        settingsService.set(key, value);
      }
    }
    return ResponseEntity.ok(Map.of("status", "saved"));
  }

  @Operation(summary = "Get service URL settings")
  @ApiResponses(
      value = {
        @ApiResponse(responseCode = "200", description = "Settings returned"),
        @ApiResponse(responseCode = "401", description = "Unauthorized"),
        @ApiResponse(responseCode = "403", description = "Forbidden")
      })
  @GetMapping("/services")
  public ResponseEntity<Map<String, String>> getServiceSettings() {
    return ResponseEntity.ok(
        Map.of(
            "service.separator-url",
                settingsService.get("service.separator-url", "AUDIO_SEPARATOR_URL"),
            "service.lyrics-url", settingsService.get("service.lyrics-url", "LYRICS_FETCHER_URL")));
  }

  @Operation(summary = "Update service URL settings")
  @ApiResponses(
      value = {
        @ApiResponse(responseCode = "200", description = "Settings updated"),
        @ApiResponse(responseCode = "400", description = "Invalid URL"),
        @ApiResponse(responseCode = "401", description = "Unauthorized"),
        @ApiResponse(responseCode = "403", description = "Forbidden")
      })
  @PutMapping("/services")
  public ResponseEntity<Map<String, String>> updateServiceSettings(
      @RequestBody Map<String, String> settings) {
    for (String key : SERVICE_URL_KEYS) {
      String value = settings.get(key);
      if (value != null && !value.isBlank()) {
        if (value.length() > MAX_SETTING_VALUE_LENGTH) {
          return ResponseEntity.badRequest()
              .body(Map.of("error", "Value too long for key: " + key));
        }
        if (!isValidServiceUrl(value)) {
          return ResponseEntity.badRequest()
              .body(Map.of("error", "Invalid URL for key: " + key + " (must be http or https)"));
        }
        settingsService.set(key, value);
      }
    }
    return ResponseEntity.ok(Map.of("status", "saved"));
  }

  private static final int MAX_SETTING_VALUE_LENGTH = 500;

  private static boolean isValidServiceUrl(String value) {
    try {
      URI uri = new URI(value);
      String scheme = uri.getScheme();
      return ("http".equals(scheme) || "https".equals(scheme)) && uri.getHost() != null;
    } catch (URISyntaxException e) {
      return false;
    }
  }

  private String mask(String value) {
    if (value == null || value.isBlank()) return "";
    if (value.length() <= 4) return "****";
    return "****" + value.substring(value.length() - 4);
  }
}
