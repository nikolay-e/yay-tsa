package com.example.mediaserver.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public record QueryResultDto<T>(
    @JsonProperty("Items") List<T> items,
    @JsonProperty("TotalRecordCount") long totalRecordCount,
    @JsonProperty("StartIndex") int startIndex
) {}
