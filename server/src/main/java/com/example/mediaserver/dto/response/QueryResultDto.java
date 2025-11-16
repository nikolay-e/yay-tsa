package com.example.mediaserver.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * Query result container matching Jellyfin API specification.
 * Used for paginated responses like GET /Items
 */
public record QueryResultDto<T>(
    @JsonProperty("Items") List<T> items,
    @JsonProperty("TotalRecordCount") int totalRecordCount,
    @JsonProperty("StartIndex") int startIndex
) {
    /**
     * Create an empty result
     */
    public static <T> QueryResultDto<T> empty() {
        return new QueryResultDto<>(List.of(), 0, 0);
    }

    /**
     * Create a result from a list with automatic count
     */
    public static <T> QueryResultDto<T> of(List<T> items, int startIndex, int totalCount) {
        return new QueryResultDto<>(items, totalCount, startIndex);
    }
}