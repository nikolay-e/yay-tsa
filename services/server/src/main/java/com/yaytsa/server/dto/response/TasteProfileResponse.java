package com.yaytsa.server.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.OffsetDateTime;

public record TasteProfileResponse(
    @JsonProperty("user_id") String userId,
    @JsonProperty("profile") Object profile,
    @JsonProperty("summary_text") String summaryText,
    @JsonProperty("rebuilt_at") OffsetDateTime rebuiltAt) {}
