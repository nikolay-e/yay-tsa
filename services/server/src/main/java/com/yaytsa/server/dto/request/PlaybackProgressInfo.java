package com.yaytsa.server.dto.request;

import com.fasterxml.jackson.annotation.JsonProperty;

public class PlaybackProgressInfo {

  @JsonProperty("ItemId")
  private String itemId;

  @JsonProperty("PositionTicks")
  private Long positionTicks;

  @JsonProperty("IsPaused")
  private Boolean isPaused;

  @JsonProperty("PlayMethod")
  private String playMethod;

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

  public Boolean getIsPaused() {
    return isPaused;
  }

  public void setIsPaused(Boolean isPaused) {
    this.isPaused = isPaused;
  }

  public String getPlayMethod() {
    return playMethod;
  }

  public void setPlayMethod(String playMethod) {
    this.playMethod = playMethod;
  }
}
