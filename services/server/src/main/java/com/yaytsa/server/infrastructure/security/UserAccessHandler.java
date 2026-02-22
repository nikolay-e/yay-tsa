package com.yaytsa.server.infrastructure.security;

import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

@Component("userAccess")
public class UserAccessHandler {

  public boolean isOwnerOrAdmin(String userId, Authentication authentication) {
    if (authentication == null
        || !(authentication.getPrincipal() instanceof AuthenticatedUser user)) {
      return false;
    }
    if (user.getUserEntity().isAdmin()) {
      return true;
    }
    try {
      UUID requestedId = UUID.fromString(userId);
      return requestedId.equals(user.getUserEntity().getId());
    } catch (IllegalArgumentException e) {
      return false;
    }
  }
}
