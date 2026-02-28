package com.yaytsa.server.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.OffsetDateTime;

public record PlaybackSignalResponse(
    @JsonProperty("id") String id,
    @JsonProperty("session_id") String sessionId,
    @JsonProperty("signal_type") String signalType,
    @JsonProperty("track_id") String trackId,
    @JsonProperty("queue_entry_id") String queueEntryId,
    @JsonProperty("context") Object context,
    @JsonProperty("created_at") OffsetDateTime createdAt,
    @JsonProperty("trigger_fired") boolean triggerFired) {}
