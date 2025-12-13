package com.example.mediaserver.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * Controller for system information endpoints.
 * Provides server status, version, and configuration information.
 */
@RestController
@RequestMapping("/System")
@Tag(name = "System", description = "System information and configuration")
public class SystemController {

    @Value("${spring.application.name:yaytsa-media-server}")
    private String applicationName;

    @Value("${server.port:8080}")
    private String serverPort;

    @Operation(summary = "Get public system information",
              description = "Retrieve server information without authentication. " +
                           "Used by clients to verify server compatibility.")
    @ApiResponse(responseCode = "200", description = "Server information retrieved successfully")
    @GetMapping("/Info/Public")
    public ResponseEntity<Map<String, Object>> getPublicSystemInfo() {
        Map<String, Object> info = new HashMap<>();

        // Core server identification
        info.put("LocalAddress", "http://localhost:" + serverPort);
        info.put("ServerName", "Yaytsa Media Server");
        info.put("Version", "0.1.0");
        info.put("ProductName", "Yaytsa");
        info.put("OperatingSystem", System.getProperty("os.name"));
        info.put("Id", "yaytsa-server");

        // Startup time (simplified)
        info.put("StartupWizardCompleted", true);

        // Jellyfin compatibility flags
        info.put("SupportsLibraryMonitor", true);
        info.put("CanSelfRestart", false);
        info.put("CanLaunchWebBrowser", false);
        info.put("HasUpdateAvailable", false);
        info.put("HasPendingRestart", false);

        // Package information
        info.put("PackageName", "yaytsa-media-server");
        info.put("EncoderLocation", "System");

        return ResponseEntity.ok(info);
    }

    @Operation(summary = "Get full system information",
              description = "Retrieve complete server information (requires authentication)")
    @GetMapping("/Info")
    public ResponseEntity<Map<String, Object>> getSystemInfo(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestParam(value = "api_key", required = false) String apiKey) {

        // TODO: Phase 9 - Add full system info with authentication
        Map<String, Object> info = new HashMap<>();
        info.put("ServerName", "Yaytsa Media Server");
        info.put("Version", "0.1.0");
        info.put("Id", "yaytsa-server");
        info.put("OperatingSystem", System.getProperty("os.name"));
        info.put("HasPendingRestart", false);
        info.put("IsShuttingDown", false);

        return ResponseEntity.ok(info);
    }

    @Operation(summary = "Ping server",
              description = "Simple health check endpoint")
    @GetMapping("/Ping")
    public ResponseEntity<String> ping() {
        return ResponseEntity.ok("Yaytsa Media Server");
    }
}
