package com.yaytsa.server.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.UUID;

public record RadioSeedsResponse(@JsonProperty("seeds") List<RadioSeed> seeds) {

  public record RadioSeed(
      @JsonProperty("track_id") UUID trackId,
      @JsonProperty("name") String name,
      @JsonProperty("artist_name") String artistName,
      @JsonProperty("album_name") String albumName,
      @JsonProperty("album_id") UUID albumId,
      @JsonProperty("image_tag") String imageTag) {}
}
