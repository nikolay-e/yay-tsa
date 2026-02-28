package com.yaytsa.server.dto.request;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Map;

public record SubmitPlaybackSignalRequest(
    @JsonProperty("signal_type") String signalType,
    @JsonProperty("track_id") String trackId,
    @JsonProperty("queue_entry_id") String queueEntryId,
    @JsonProperty("context") Map<String, Object> context) {}
