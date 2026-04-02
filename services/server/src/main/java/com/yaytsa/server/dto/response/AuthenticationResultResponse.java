package com.yaytsa.server.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;

public record AuthenticationResultResponse(
    @JsonProperty("User") UserResponse user,
    @JsonProperty("SessionInfo") SessionInfoResponse sessionInfo,
    @JsonProperty("AccessToken") String accessToken,
    @JsonProperty("ServerId") String serverId) {
  public static AuthenticationResultResponse create(
      UserResponse user, SessionInfoResponse sessionInfo, String accessToken) {
    return new AuthenticationResultResponse(user, sessionInfo, accessToken, "yaytsa-server");
  }
}
