package com.example.mediaserver.dto.request;

import com.fasterxml.jackson.annotation.JsonProperty;

public class PlaybackStopInfo {

    @JsonProperty("ItemId")
    private String itemId;

    @JsonProperty("PositionTicks")
    private Long positionTicks;

    public String getItemId() {
        return itemId;
    }

    public void setItemId(String itemId) {
        this.itemId = itemId;
    }

    public Long getPositionTicks() {
        return positionTicks;
    }

    public void setPositionTicks(Long positionTicks) {
        this.positionTicks = positionTicks;
    }
}
