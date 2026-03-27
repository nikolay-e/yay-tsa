package com.yaytsa.server.dto.response;

import java.util.List;
import java.util.UUID;

public record RadioSeedsResponse(List<RadioSeed> seeds) {

  public record RadioSeed(
      UUID trackId,
      String name,
      String artistName,
      String albumName,
      UUID albumId,
      String imageTag) {}
}
