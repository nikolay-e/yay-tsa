package com.yaytsa.server.error;

import java.util.UUID;

public class PlaylistEntryNotFoundException extends ResourceNotFoundException {
  public PlaylistEntryNotFoundException(String message) {
    super(ResourceType.PlaylistEntry, message);
  }

  public PlaylistEntryNotFoundException(UUID entryId) {
    super(ResourceType.PlaylistEntry, entryId);
  }
}
