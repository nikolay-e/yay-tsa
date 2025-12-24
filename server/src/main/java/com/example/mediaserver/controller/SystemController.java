package com.example.mediaserver.controller;

import com.example.mediaserver.dto.response.SystemInfoDto;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/System")
@Tag(name = "System", description = "System information and configuration")
public class SystemController {

    @Value("${yaytsa.server.name:Yaytsa Media Server}")
    private String serverName;

    @Value("${yaytsa.server.version:0.1.0}")
    private String version;

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

    @Operation(summary = "Get public system information",
              description = "Retrieve server information without authentication. " +
                           "Used by clients to verify server compatibility.")
    @ApiResponse(responseCode = "200", description = "Server information retrieved successfully")
    @GetMapping("/Info/Public")
    public ResponseEntity<SystemInfoDto> getPublicSystemInfo() {
        SystemInfoDto info = SystemInfoDto.publicInfo(serverName, version, serverId, getLocalAddress());
        return ResponseEntity.ok(info);
    }

    @Operation(summary = "Get full system information",
              description = "Retrieve complete server information (requires authentication)")
    @GetMapping("/Info")
    public ResponseEntity<SystemInfoDto> getSystemInfo(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestParam(value = "api_key", required = false) String apiKey) {

        SystemInfoDto info = SystemInfoDto.fullInfo(serverName, version, serverId, getLocalAddress(), null);
        return ResponseEntity.ok(info);
    }

    @Operation(summary = "Ping server",
              description = "Simple health check endpoint")
    @GetMapping("/Ping")
    public ResponseEntity<String> ping() {
        return ResponseEntity.ok("Yaytsa Media Server");
    }
}
