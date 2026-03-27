package com.yaytsa.server.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.OffsetDateTime;

public record ListeningSessionResponse(
    String id,
    String userId,
    Object state,
    OffsetDateTime startedAt,
    OffsetDateTime lastActivityAt,
    OffsetDateTime endedAt,
    String sessionSummary,
    @JsonProperty("isRadioMode") boolean isRadioMode,
    String seedTrackId) {}
