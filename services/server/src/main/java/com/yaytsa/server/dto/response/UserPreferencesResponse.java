package com.yaytsa.server.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.OffsetDateTime;

public record UserPreferencesResponse(
    @JsonProperty("user_id") String userId,
    @JsonProperty("hard_rules") Object hardRules,
    @JsonProperty("soft_prefs") Object softPrefs,
    @JsonProperty("dj_style") Object djStyle,
    @JsonProperty("red_lines") Object redLines,
    @JsonProperty("updated_at") OffsetDateTime updatedAt) {}
