package com.example.mediaserver.dto.request;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public record CreatePlaylistRequest(
    @JsonProperty("Name") String name,
    @JsonProperty("UserId") String userId,
    @JsonProperty("Ids") List<String> ids,
    @JsonProperty("MediaType") String mediaType,
    @JsonProperty("IsPublic") Boolean isPublic
) {
}
