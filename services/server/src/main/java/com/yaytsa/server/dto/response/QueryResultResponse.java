package com.yaytsa.server.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public record QueryResultResponse<T>(
    @JsonProperty("Items") List<T> items,
    @JsonProperty("TotalRecordCount") int totalRecordCount,
    @JsonProperty("StartIndex") int startIndex) {
  public static <T> QueryResultResponse<T> empty() {
    return new QueryResultResponse<>(List.of(), 0, 0);
  }

  public static <T> QueryResultResponse<T> of(List<T> items, int startIndex, int totalCount) {
    return new QueryResultResponse<>(items, totalCount, startIndex);
  }
}
