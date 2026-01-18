package com.yaytsa.server.error;

import java.util.Collection;
import java.util.UUID;

public class ResourceNotFoundException extends RuntimeException {
  private final ResourceType resourceType;

  public ResourceNotFoundException(ResourceType resourceType, String message) {
    super(message);
    this.resourceType = resourceType;
  }

  public ResourceNotFoundException(ResourceType resourceType, UUID id) {
    super(resourceType.getDisplayName() + " not found: " + id);
    this.resourceType = resourceType;
  }

  public ResourceNotFoundException(ResourceType resourceType, Collection<UUID> ids) {
    super(resourceType.getDisplayName() + "s not found: " + ids);
    this.resourceType = resourceType;
  }

  public ResourceType getResourceType() {
    return resourceType;
  }
}
