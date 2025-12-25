package com.yaytsa.server.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * Query result container matching Jellyfin API specification.
 * Used for paginated responses like GET /Items
 */
public record QueryResultResponse<T>(
    @JsonProperty("Items") List<T> items,
    @JsonProperty("TotalRecordCount") int totalRecordCount,
    @JsonProperty("StartIndex") int startIndex
) {
    /**
     * Create an empty result
     */
    public static <T> QueryResultResponse<T> empty() {
        return new QueryResultResponse<>(List.of(), 0, 0);
    }

    /**
     * Create a result from a list with automatic count
     */
    public static <T> QueryResultResponse<T> of(List<T> items, int startIndex, int totalCount) {
        return new QueryResultResponse<>(items, totalCount, startIndex);
    }
}
