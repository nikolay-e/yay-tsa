package com.yaytsa.server.error;

import java.util.Collection;
import java.util.UUID;

public class ItemNotFoundException extends ResourceNotFoundException {
  public ItemNotFoundException(String message) {
    super(ResourceType.Item, message);
  }

  public ItemNotFoundException(UUID itemId) {
    super(ResourceType.Item, itemId);
  }

  public ItemNotFoundException(Collection<UUID> itemIds) {
    super(ResourceType.Item, itemIds);
  }
}
