package com.yaytsa.server.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Authentication result DTO matching Jellyfin API specification. This is the response from POST
 * /Users/AuthenticateByName
 */
public record AuthenticationResultResponse(
    @JsonProperty("User") UserResponse user,
    @JsonProperty("SessionInfo") SessionInfoResponse sessionInfo,
    @JsonProperty("AccessToken") String accessToken,
    @JsonProperty("ServerId") String serverId) {
  /** Create an authentication result with all required fields */
  public static AuthenticationResultResponse create(
      UserResponse user, SessionInfoResponse sessionInfo, String accessToken) {
    return new AuthenticationResultResponse(user, sessionInfo, accessToken, "yaytsa-server");
  }
}
