package dev.yaytsa.persistence.auth.mapper

import dev.yaytsa.domain.auth.ApiToken
import dev.yaytsa.domain.auth.ApiTokenId
import dev.yaytsa.domain.auth.DeviceId
import dev.yaytsa.domain.auth.UserAggregate
import dev.yaytsa.persistence.auth.TokenHasher
import dev.yaytsa.persistence.auth.entity.ApiTokenEntity
import dev.yaytsa.persistence.auth.entity.UserEntity
import dev.yaytsa.shared.AggregateVersion
import dev.yaytsa.shared.UserId
import java.util.UUID

fun UserEntity.toDomain(tokens: List<ApiTokenEntity>): UserAggregate =
    UserAggregate(
        id = UserId(id.toString()),
        username = username,
        passwordHash = passwordHash,
        displayName = displayName,
        email = email,
        isAdmin = isAdmin,
        isActive = isActive,
        createdAt = createdAt,
        updatedAt = updatedAt,
        lastLoginAt = lastLoginAt,
        apiTokens = tokens.map { it.toDomain() },
        version = AggregateVersion(version),
    )

fun UserAggregate.toEntity(): UserEntity =
    UserEntity(
        id = UUID.fromString(id.value),
        username = username,
        passwordHash = passwordHash,
        displayName = displayName,
        email = email,
        isAdmin = isAdmin,
        isActive = isActive,
        createdAt = createdAt,
        updatedAt = updatedAt,
        lastLoginAt = lastLoginAt,
        version = version.value,
    )

fun UserAggregate.toTokenEntities(existingTokenIds: Set<UUID> = emptySet()): List<ApiTokenEntity> =
    apiTokens.map { token ->
        val tokenId = UUID.fromString(token.id.value)
        ApiTokenEntity(
            id = tokenId,
            userId = UUID.fromString(id.value),
            // Hash only NEW tokens; existing ones already have hashed values from DB
            token = if (tokenId in existingTokenIds) token.token else TokenHasher.hash(token.token),
            deviceId = token.deviceId.value,
            deviceName = token.deviceName,
            createdAt = token.createdAt,
            lastUsedAt = token.lastUsedAt,
            expiresAt = token.expiresAt,
            revoked = token.revoked,
        )
    }

fun ApiTokenEntity.toDomain(): ApiToken =
    ApiToken(
        id = ApiTokenId(id.toString()),
        token = token,
        deviceId = DeviceId(deviceId),
        deviceName = deviceName,
        createdAt = createdAt,
        lastUsedAt = lastUsedAt,
        expiresAt = expiresAt,
        revoked = revoked,
    )
