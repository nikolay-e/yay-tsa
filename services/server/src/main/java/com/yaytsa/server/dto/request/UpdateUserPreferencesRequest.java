package com.yaytsa.server.dto.request;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Map;

public record UpdateUserPreferencesRequest(
    @JsonProperty("hard_rules") Map<String, Object> hardRules,
    @JsonProperty("soft_prefs") Map<String, Object> softPrefs,
    @JsonProperty("dj_style") Map<String, Object> djStyle,
    @JsonProperty("red_lines") Object redLines) {}
