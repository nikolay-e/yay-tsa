package com.yaytsa.server.domain.service;

import java.util.Set;
import java.util.UUID;

public record SessionSkipContext(
    Set<UUID> skippedTrackIds,
    Set<String> skippedArtistNames,
    float avgSkippedEnergy,
    float avgSkippedValence,
    boolean hasSkipMood) {

  public static final SessionSkipContext EMPTY =
      new SessionSkipContext(Set.of(), Set.of(), 0f, 0f, false);
}
