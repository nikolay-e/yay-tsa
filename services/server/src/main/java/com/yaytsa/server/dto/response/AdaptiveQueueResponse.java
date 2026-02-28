package com.yaytsa.server.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public record AdaptiveQueueResponse(
    @JsonProperty("sessionId") String sessionId,
    @JsonProperty("tracks") List<AdaptiveQueueEntryResponse> tracks,
    @JsonProperty("totalCount") int totalCount) {}
