package com.yaytsa.server.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.OffsetDateTime;

public record ListeningSessionResponse(
    @JsonProperty("id") String id,
    @JsonProperty("user_id") String userId,
    @JsonProperty("state") Object state,
    @JsonProperty("started_at") OffsetDateTime startedAt,
    @JsonProperty("last_activity_at") OffsetDateTime lastActivityAt,
    @JsonProperty("ended_at") OffsetDateTime endedAt,
    @JsonProperty("session_summary") String sessionSummary,
    @JsonProperty("is_radio_mode") boolean isRadioMode,
    @JsonProperty("seed_track_id") String seedTrackId) {}
