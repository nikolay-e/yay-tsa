package com.yaytsa.server.dto.response;

import java.time.OffsetDateTime;

public record UserPreferencesResponse(
    String userId,
    Object hardRules,
    Object softPrefs,
    Object djStyle,
    Object redLines,
    OffsetDateTime updatedAt) {}
