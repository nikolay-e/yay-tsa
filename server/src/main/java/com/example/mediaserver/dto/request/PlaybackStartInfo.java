package com.example.mediaserver.dto.request;

import com.fasterxml.jackson.annotation.JsonProperty;

public class PlaybackStartInfo {

    @JsonProperty("ItemId")
    private String itemId;

    @JsonProperty("PositionTicks")
    private Long positionTicks;

    @JsonProperty("IsPaused")
    private Boolean isPaused;

    @JsonProperty("CanSeek")
    private Boolean canSeek;

    @JsonProperty("PlayMethod")
    private String playMethod;

    @JsonProperty("VolumeLevel")
    private Integer volumeLevel;

    @JsonProperty("IsMuted")
    private Boolean isMuted;

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

    public Boolean getCanSeek() {
        return canSeek;
    }

    public void setCanSeek(Boolean canSeek) {
        this.canSeek = canSeek;
    }

    public String getPlayMethod() {
        return playMethod;
    }

    public void setPlayMethod(String playMethod) {
        this.playMethod = playMethod;
    }

    public Integer getVolumeLevel() {
        return volumeLevel;
    }

    public void setVolumeLevel(Integer volumeLevel) {
        this.volumeLevel = volumeLevel;
    }

    public Boolean getIsMuted() {
        return isMuted;
    }

    public void setIsMuted(Boolean isMuted) {
        this.isMuted = isMuted;
    }
}
