package com.yaytsa.server.controller;

import com.yaytsa.server.dto.response.SystemInfoResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/System")
@Tag(name = "System", description = "System information and configuration")
public class SystemController {

  @Value("${yaytsa.server.name:Yay-Tsa Media Server}")
  private String serverName;

  private static final String JELLYFIN_COMPAT_VERSION = "10.10.0";

  @Value("${yaytsa.server.version:${APP_VERSION:0.0.0-dev}}")
  private String appVersion;

  @Value("${yaytsa.server.id:yaytsa-default}")
  private String serverId;

  @Value("${server.port:8096}")
  private String serverPort;

  @Value("${yaytsa.public-url:}")
  private String publicUrl;

  private String getLocalAddress() {
    if (publicUrl != null && !publicUrl.isBlank()) {
      return publicUrl;
    }
    return "http://localhost:" + serverPort;
  }

  @Operation(
      summary = "Get public system information",
      description =
          "Retrieve server information without authentication. "
              + "Used by clients to verify server compatibility.")
  @ApiResponse(responseCode = "200", description = "Server information retrieved successfully")
  @SecurityRequirements
  @GetMapping("/Info/Public")
  public ResponseEntity<SystemInfoResponse> getPublicSystemInfo(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @RequestHeader(value = "X-Emby-Authorization", required = false) String embyAuth) {
    String resolvedVersion =
        isEmbyClient(authorization, embyAuth) ? JELLYFIN_COMPAT_VERSION : appVersion;
    SystemInfoResponse info =
        SystemInfoResponse.publicInfo(serverName, resolvedVersion, serverId, getLocalAddress());
    return ResponseEntity.ok(info);
  }

  @Operation(
      summary = "Get full system information",
      description = "Retrieve complete server information (requires authentication)")
  @ApiResponses(
      value = {
        @ApiResponse(responseCode = "200", description = "System info returned"),
        @ApiResponse(responseCode = "401", description = "Unauthorized")
      })
  @GetMapping("/Info")
  public ResponseEntity<SystemInfoResponse> getSystemInfo(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @RequestParam(value = "api_key", required = false) String apiKey) {

    String resolvedVersion =
        isEmbyClient(authorization, null) ? JELLYFIN_COMPAT_VERSION : appVersion;
    SystemInfoResponse info =
        SystemInfoResponse.fullInfo(serverName, resolvedVersion, serverId, getLocalAddress(), null);
    return ResponseEntity.ok(info);
  }

  private static boolean isEmbyClient(String authorization, String embyAuth) {
    if (embyAuth != null && !embyAuth.isBlank()) return true;
    if (authorization != null && authorization.startsWith("MediaBrowser ")) return true;
    return false;
  }

  @Operation(summary = "Ping server", description = "Simple health check endpoint")
  @ApiResponses(
      value = {
        @ApiResponse(responseCode = "200", description = "Pong"),
        @ApiResponse(responseCode = "401", description = "Unauthorized")
      })
  @GetMapping("/Ping")
  public ResponseEntity<String> ping() {
    return ResponseEntity.ok("Yay-Tsa Media Server");
  }
}
