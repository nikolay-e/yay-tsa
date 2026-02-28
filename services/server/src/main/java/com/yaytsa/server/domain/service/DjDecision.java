package com.yaytsa.server.domain.service;

import java.util.List;
import java.util.UUID;

public record DjDecision(
    long baseQueueVersion, DjIntent intent, List<QueueEdit> edits, String sessionSummaryUpdate) {

  public record DjIntent(String sessionArc, String reasoning) {}

  public record QueueEdit(
      EditAction action, int position, UUID trackId, String reason, String intentLabel) {}

  public enum EditAction {
    INSERT,
    REMOVE,
    REORDER
  }
}
