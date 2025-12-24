package com.example.mediaserver.mapper;

import com.example.mediaserver.dto.response.UserDto;
import com.example.mediaserver.infra.persistence.entity.UserEntity;
import org.springframework.stereotype.Component;

@Component
public class UserMapper {

    private static final String SERVER_ID = "yaytsa-server";
    private static final String SERVER_NAME = "Yaytsa Media Server";

    public UserDto toDto(UserEntity entity) {
        if (entity == null) {
            return null;
        }

        return new UserDto(
            entity.getDisplayName() != null ? entity.getDisplayName() : entity.getUsername(),
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
            createConfiguration()
        );
    }

    private UserDto.UserPolicyDto createPolicy(UserEntity entity) {
        return new UserDto.UserPolicyDto(
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
            "CreateAndJoinGroups"
        );
    }

    private UserDto.UserConfigurationDto createConfiguration() {
        return new UserDto.UserConfigurationDto(
            "",
            true,
            "",
            false,
            new String[]{},
            "Default",
            false,
            false,
            new String[]{},
            new String[]{},
            new String[]{},
            true,
            true,
            true,
            true
        );
    }
}
