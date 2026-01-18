package com.yaytsa.server.error;

import java.util.UUID;

public class PlaylistNotFoundException extends ResourceNotFoundException {
  public PlaylistNotFoundException(String message) {
    super(ResourceType.Playlist, message);
  }

  public PlaylistNotFoundException(UUID playlistId) {
    super(ResourceType.Playlist, playlistId);
  }
}
