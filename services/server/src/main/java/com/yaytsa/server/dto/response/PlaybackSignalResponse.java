package com.yaytsa.server.dto.response;

import java.time.OffsetDateTime;

public record PlaybackSignalResponse(
    String id,
    String sessionId,
    String signalType,
    String trackId,
    String queueEntryId,
    Object context,
    OffsetDateTime createdAt,
    boolean triggerFired) {}
