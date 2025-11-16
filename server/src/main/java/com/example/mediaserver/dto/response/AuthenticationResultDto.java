package com.example.mediaserver.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Authentication result DTO matching Jellyfin API specification.
 * This is the response from POST /Users/AuthenticateByName
 */
public record AuthenticationResultDto(
    @JsonProperty("User") UserDto user,
    @JsonProperty("SessionInfo") SessionInfoDto sessionInfo,
    @JsonProperty("AccessToken") String accessToken,
    @JsonProperty("ServerId") String serverId
) {
    /**
     * Create an authentication result with all required fields
     */
    public static AuthenticationResultDto create(UserDto user, SessionInfoDto sessionInfo, String accessToken) {
        return new AuthenticationResultDto(
            user,
            sessionInfo,
            accessToken,
            "yaytsa-server"
        );
    }
}