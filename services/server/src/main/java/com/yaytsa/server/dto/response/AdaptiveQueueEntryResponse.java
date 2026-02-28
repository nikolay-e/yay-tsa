package com.yaytsa.server.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.OffsetDateTime;

public record AdaptiveQueueEntryResponse(
    @JsonProperty("id") String id,
    @JsonProperty("trackId") String trackId,
    @JsonProperty("name") String name,
    @JsonProperty("artistName") String artistName,
    @JsonProperty("albumName") String albumName,
    @JsonProperty("durationMs") Long durationMs,
    @JsonProperty("position") int position,
    @JsonProperty("addedReason") String addedReason,
    @JsonProperty("intentLabel") String intentLabel,
    @JsonProperty("status") String status,
    @JsonProperty("features") TrackFeaturesDto features,
    @JsonProperty("queueVersion") long queueVersion,
    @JsonProperty("addedAt") OffsetDateTime addedAt,
    @JsonProperty("playedAt") OffsetDateTime playedAt) {

  public record TrackFeaturesDto(
      Float bpm, Float energy, Float valence, Float arousal, Float danceability) {}
}
