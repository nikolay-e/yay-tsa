package com.yaytsa.server.error;

public enum ResourceType {
  Item("Item"),
  Playlist("Playlist"),
  PlaylistEntry("Playlist entry"),
  User("User");

  private final String displayName;

  ResourceType(String displayName) {
    this.displayName = displayName;
  }

  public String getDisplayName() {
    return displayName;
  }
}
