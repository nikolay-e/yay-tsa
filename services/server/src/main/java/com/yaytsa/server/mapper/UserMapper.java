package com.yaytsa.server.mapper;

import com.yaytsa.server.dto.response.UserResponse;
import com.yaytsa.server.infrastructure.persistence.entity.UserEntity;
import org.springframework.stereotype.Component;

@Component
public class UserMapper {

  private static final String SERVER_ID = "yaytsa-server";
  private static final String SERVER_NAME = "Yay-Tsa Media Server";

  public UserResponse toDto(UserEntity entity) {
    if (entity == null) {
      return null;
    }

    return new UserResponse(
        entity.getUsername(),
        SERVER_ID,
        SERVER_NAME,
        entity.getId().toString(),
        null,
        entity.getPasswordHash() != null && !entity.getPasswordHash().isEmpty(),
        entity.getPasswordHash() != null && !entity.getPasswordHash().isEmpty(),
        false,
        false,
        entity.getLastLoginAt(),
        entity.getUpdatedAt(),
        createPolicy(entity),
        createConfiguration());
  }

  private UserResponse.UserPolicy createPolicy(UserEntity entity) {
    return new UserResponse.UserPolicy(
        entity.isAdmin(),
        false,
        !entity.isActive(),
        true,
        entity.isAdmin(),
        true,
        true,
        false,
        true,
        true,
        true,
        true,
        true,
        false,
        entity.isAdmin(),
        true,
        true,
        false,
        true,
        true,
        true,
        0,
        3,
        0,
        "Jellyfin.Server.Implementations.Users.DefaultAuthenticationProvider",
        "Jellyfin.Server.Implementations.Users.DefaultPasswordResetProvider",
        "CreateAndJoinGroups");
  }

  private UserResponse.UserConfiguration createConfiguration() {
    return new UserResponse.UserConfiguration(
        "",
        true,
        "",
        false,
        new String[] {},
        "Default",
        false,
        false,
        new String[] {},
        new String[] {},
        new String[] {},
        true,
        true,
        true,
        true);
  }
}
