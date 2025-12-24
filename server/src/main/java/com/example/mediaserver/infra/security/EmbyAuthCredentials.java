package com.example.mediaserver.infra.security;

public record EmbyAuthCredentials(
    String token,
    String deviceId,
    String deviceName,
    String clientName,
    String clientVersion
) {
    public static EmbyAuthCredentials fromQueryParams(String token, String deviceId) {
        return new EmbyAuthCredentials(token, deviceId, null, null, null);
    }
}
