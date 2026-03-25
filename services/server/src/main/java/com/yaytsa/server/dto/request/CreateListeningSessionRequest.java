package com.yaytsa.server.dto.request;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Map;
import java.util.UUID;

public record CreateListeningSessionRequest(
    @JsonProperty("state") Map<String, Object> state,
    @JsonProperty("seed_track_id") UUID seedTrackId) {}
