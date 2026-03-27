package com.yaytsa.server.dto.response;

import java.time.OffsetDateTime;

public record TasteProfileResponse(
    String userId, Object profile, String summaryText, OffsetDateTime rebuiltAt) {}
