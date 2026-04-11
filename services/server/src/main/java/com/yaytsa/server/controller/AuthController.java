package com.yaytsa.server.controller;

import com.yaytsa.server.domain.service.AuthService;
import com.yaytsa.server.dto.request.AuthenticateByNameRequest;
import com.yaytsa.server.dto.response.AuthenticationResultResponse;
import com.yaytsa.server.dto.response.SessionInfoResponse;
import com.yaytsa.server.dto.response.UserResponse;
import com.yaytsa.server.infrastructure.security.AuthenticatedUser;
import com.yaytsa.server.infrastructure.security.EmbyAuthHeaderParser;
import com.yaytsa.server.infrastructure.security.LoginRateLimiter;
import com.yaytsa.server.mapper.UserMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.Map;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

@RestController
@Tag(name = "Authentication", description = "User authentication and management")
@Slf4j
public class AuthController {

  private final AuthService authService;
  private final UserMapper userMapper;
  private final LoginRateLimiter loginRateLimiter;

  public AuthController(
      AuthService authService, UserMapper userMapper, LoginRateLimiter loginRateLimiter) {
    this.authService = authService;
    this.userMapper = userMapper;
    this.loginRateLimiter = loginRateLimiter;
  }

  @Operation(
      summary = "Authenticate user by username and password",
      description =
          "Login with username and password to receive an authentication token. "
              + "IMPORTANT: Use 'Pw' field for password (not 'Password')!")
  @ApiResponses(
      value = {
        @ApiResponse(
            responseCode = "200",
            description = "Successfully authenticated",
            content =
                @Content(schema = @Schema(implementation = AuthenticationResultResponse.class))),
        @ApiResponse(responseCode = "401", description = "Invalid credentials"),
        @ApiResponse(responseCode = "400", description = "Bad request")
      })
  @PostMapping("/Users/AuthenticateByName")
  public ResponseEntity<AuthenticationResultResponse> authenticateByName(
      @Valid @RequestBody AuthenticateByNameRequest request,
      @RequestHeader(value = "X-Emby-Authorization", required = false) String embyAuth,
      HttpServletRequest httpRequest) {

    String clientIp = resolveClientIp(httpRequest);
    if (loginRateLimiter.isBlocked(clientIp, request.username())) {
      log.warn("Rate limit exceeded for IP: {} / user: {}", clientIp, request.username());
      return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).build();
    }

    Map<String, String> headerParts = EmbyAuthHeaderParser.parse(embyAuth);
    String deviceId = headerParts.getOrDefault("DeviceId", UUID.randomUUID().toString());
    String deviceName = headerParts.getOrDefault("Device", "Web Browser");
    String client = headerParts.getOrDefault("Client", "Yay-Tsa Web");
    String version = headerParts.getOrDefault("Version", "0.1.0");

    try {
      AuthService.AuthenticationResult result =
          authService.authenticateByName(
              request.username(), request.password(), deviceId, deviceName, client, version);

      loginRateLimiter.recordSuccess(clientIp, request.username());

      UserResponse userDto = userMapper.toDto(result.user());
      SessionInfoResponse sessionInfo =
          SessionInfoResponse.forAuth(
              result.sessionId(),
              result.user().getId().toString(),
              result.user().getUsername(),
              result.deviceId(),
              result.deviceName(),
              result.client());

      AuthenticationResultResponse response =
          AuthenticationResultResponse.create(userDto, sessionInfo, result.accessToken());

      log.info(
          "User authenticated successfully: {} from device: {}",
          result.user().getUsername(),
          result.deviceId());

      return ResponseEntity.ok(response);

    } catch (AuthService.AuthenticationException e) {
      loginRateLimiter.recordFailure(clientIp, request.username());
      log.warn("Authentication failed for user: {}", request.username());
      return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
    }
  }

  @Operation(
      summary = "Get current user",
      description = "Retrieve the currently authenticated user's profile")
  @ApiResponses(
      value = {
        @ApiResponse(responseCode = "200", description = "Current user returned"),
        @ApiResponse(responseCode = "401", description = "Unauthorized")
      })
  @GetMapping("/Users/Me")
  public ResponseEntity<UserResponse> getCurrentUser() {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

    if (authentication == null || !(authentication.getPrincipal() instanceof AuthenticatedUser)) {
      return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
    }

    AuthenticatedUser authenticatedUser = (AuthenticatedUser) authentication.getPrincipal();
    UserResponse userDto = userMapper.toDto(authenticatedUser.getUserEntity());

    return ResponseEntity.ok(userDto);
  }

  @Operation(
      summary = "Logout user session",
      description = "Revoke the current authentication token")
  @ApiResponses(
      value = {
        @ApiResponse(responseCode = "204", description = "Successfully logged out"),
        @ApiResponse(responseCode = "401", description = "Unauthorized")
      })
  @PostMapping("/Sessions/Logout")
  public ResponseEntity<Void> logout(
      @RequestHeader(value = "X-Emby-Authorization", required = false) String embyAuth,
      @RequestParam(value = "api_key", required = false) String apiKey) {

    String token = extractToken(embyAuth, apiKey);

    if (token != null && !token.isBlank()) {
      authService.logout(token);
      log.info("User logged out successfully");
    }

    return ResponseEntity.noContent().build();
  }

  private String extractToken(String embyAuth, String apiKey) {
    if (embyAuth != null && !embyAuth.isBlank()) {
      return EmbyAuthHeaderParser.parse(embyAuth).get("Token");
    }
    return apiKey;
  }

  private static String resolveClientIp(HttpServletRequest request) {
    String forwarded = request.getHeader("X-Forwarded-For");
    if (forwarded != null && !forwarded.isBlank()) {
      return forwarded.split(",")[0].trim();
    }
    String realIp = request.getHeader("X-Real-IP");
    if (realIp != null && !realIp.isBlank()) {
      return realIp.trim();
    }
    return request.getRemoteAddr();
  }
}
