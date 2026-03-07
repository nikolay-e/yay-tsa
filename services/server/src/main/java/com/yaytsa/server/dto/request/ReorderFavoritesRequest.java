package com.yaytsa.server.dto.request;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public record ReorderFavoritesRequest(
    @JsonProperty("UserId") String userId, @JsonProperty("ItemIds") List<String> itemIds) {}
