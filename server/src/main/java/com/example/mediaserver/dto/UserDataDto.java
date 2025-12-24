package com.example.mediaserver.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.OffsetDateTime;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record UserDataDto(
    @JsonProperty("PlaybackPositionTicks") Long playbackPositionTicks,
    @JsonProperty("PlayCount") Integer playCount,
    @JsonProperty("IsFavorite") Boolean isFavorite,
    @JsonProperty("LastPlayedDate") OffsetDateTime lastPlayedDate,
    @JsonProperty("Played") Boolean played
) {}
