package com.yaytsa.server.error;

public enum ResourceType {
  Item("Item"),
  ListeningSession("Listening session"),
  Playlist("Playlist"),
  PlaylistEntry("Playlist entry"),
  TasteProfile("Taste profile"),
  TrackFeatures("Track features"),
  User("User"),
  UserPreferences("User preferences");

  private final String displayName;

  ResourceType(String displayName) {
    this.displayName = displayName;
  }

  public String getDisplayName() {
    return displayName;
  }
}
