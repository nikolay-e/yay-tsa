package com.example.mediaserver.controller;

import com.example.mediaserver.dto.response.AuthenticationResultDto;
import com.example.mediaserver.dto.response.SessionInfoDto;
import com.example.mediaserver.dto.response.UserDto;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Controller for authentication and user management.
 * Handles login, logout, and user profile operations.
 *
 * IMPORTANT: Jellyfin clients expect specific response structures!
 * - Authentication must include User, SessionInfo, AccessToken, ServerId
 * - Password field is "Pw" (not "Password")
 * - X-Emby-Authorization header contains client info
 */
@RestController
@Tag(name = "Authentication", description = "User authentication and management")
public class AuthController {

    @Operation(summary = "Authenticate user by username and password",
              description = "Login with username and password to receive an authentication token. " +
                           "IMPORTANT: Use 'Pw' field for password (not 'Password')!")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "Successfully authenticated",
                    content = @Content(schema = @Schema(implementation = AuthenticationResultDto.class))),
        @ApiResponse(responseCode = "401", description = "Invalid credentials")
    })
    @PostMapping("/Users/AuthenticateByName")
    public ResponseEntity<AuthenticationResultDto> authenticateByName(
            @RequestBody Map<String, String> credentials,
            @RequestHeader(value = "X-Emby-Authorization", required = false) String embyAuth) {

        // Extract credentials
        // IMPORTANT: Jellyfin uses "Pw" not "Password"!
        String username = credentials.get("Username");
        String password = credentials.get("Pw");

        // Parse X-Emby-Authorization header for device info
        // Format: MediaBrowser Client="...", Device="...", DeviceId="...", Version="..."
        String deviceId = UUID.randomUUID().toString();
        String deviceName = "Web Browser";
        String client = "Yaytsa Web";

        if (embyAuth != null && !embyAuth.isEmpty()) {
            Map<String, String> headerParts = parseEmbyAuthHeader(embyAuth);
            if (headerParts.containsKey("DeviceId")) {
                deviceId = headerParts.get("DeviceId");
            }
            if (headerParts.containsKey("Device")) {
                deviceName = headerParts.get("Device");
            }
            if (headerParts.containsKey("Client")) {
                client = headerParts.get("Client");
            }
        }

        // TODO: Phase 2 - Implement actual authentication against database
        // For now, return stub response with proper structure

        String userId = UUID.randomUUID().toString();
        String sessionId = UUID.randomUUID().toString();
        String accessToken = generateToken();

        // Create proper Jellyfin-compatible response
        UserDto user = UserDto.minimal(userId, username, "yaytsa-server");
        SessionInfoDto sessionInfo = SessionInfoDto.forAuth(
            sessionId,
            userId,
            username,
            deviceId,
            deviceName,
            client
        );

        AuthenticationResultDto result = AuthenticationResultDto.create(
            user,
            sessionInfo,
            accessToken
        );

        return ResponseEntity.ok(result);
    }

    @Operation(summary = "Get user by ID",
              description = "Retrieve user profile information")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "Successfully retrieved user",
                    content = @Content(schema = @Schema(implementation = UserDto.class))),
        @ApiResponse(responseCode = "404", description = "User not found"),
        @ApiResponse(responseCode = "401", description = "Unauthorized")
    })
    @GetMapping("/Users/{userId}")
    public ResponseEntity<UserDto> getUser(
            @PathVariable String userId,
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestParam(value = "api_key", required = false) String apiKey) {

        // TODO: Phase 2 - Fetch from database
        UserDto user = UserDto.minimal(userId, "Test User", "yaytsa-server");

        return ResponseEntity.ok(user);
    }

    @Operation(summary = "Get current user",
              description = "Retrieve the currently authenticated user's profile")
    @GetMapping("/Users/Me")
    public ResponseEntity<UserDto> getCurrentUser(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestParam(value = "api_key", required = false) String apiKey) {

        // TODO: Phase 2 - Extract user from token
        String userId = UUID.randomUUID().toString();
        UserDto user = UserDto.minimal(userId, "Current User", "yaytsa-server");

        return ResponseEntity.ok(user);
    }

    @Operation(summary = "Logout user session",
              description = "Revoke the current authentication token")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "204", description = "Successfully logged out"),
        @ApiResponse(responseCode = "401", description = "Unauthorized")
    })
    @PostMapping("/Sessions/Logout")
    public ResponseEntity<Void> logout(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestParam(value = "api_key", required = false) String apiKey) {

        // TODO: Phase 2 - Revoke token in database
        return ResponseEntity.noContent().build();
    }

    /**
     * Parse X-Emby-Authorization header
     * Format: MediaBrowser Client="...", Device="...", DeviceId="...", Version="...", Token="..."
     */
    private Map<String, String> parseEmbyAuthHeader(String header) {
        Map<String, String> result = new HashMap<>();

        if (header == null || header.isEmpty()) {
            return result;
        }

        // Remove "MediaBrowser " prefix if present
        String content = header;
        if (content.startsWith("MediaBrowser ")) {
            content = content.substring("MediaBrowser ".length());
        }

        // Parse key="value" pairs
        String[] parts = content.split(",");
        for (String part : parts) {
            String trimmed = part.trim();
            int equalPos = trimmed.indexOf('=');
            if (equalPos > 0) {
                String key = trimmed.substring(0, equalPos).trim();
                String value = trimmed.substring(equalPos + 1).trim();
                // Remove quotes
                if (value.startsWith("\"") && value.endsWith("\"")) {
                    value = value.substring(1, value.length() - 1);
                }
                result.put(key, value);
            }
        }

        return result;
    }

    /**
     * Generate a secure random token
     * TODO: Phase 2 - Use proper cryptographic random generator
     */
    private String generateToken() {
        // Generate 256-bit (32 bytes) token as hex string (64 chars)
        StringBuilder token = new StringBuilder();
        for (int i = 0; i < 4; i++) {
            token.append(String.format("%016x", UUID.randomUUID().getMostSignificantBits()));
        }
        return token.substring(0, 64);
    }
}
