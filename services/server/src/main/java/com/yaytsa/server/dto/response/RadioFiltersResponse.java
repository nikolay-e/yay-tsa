package com.yaytsa.server.dto.response;

import java.util.List;

public record RadioFiltersResponse(List<String> moods, List<String> languages, long totalTracks, long analyzedTracks) {}
