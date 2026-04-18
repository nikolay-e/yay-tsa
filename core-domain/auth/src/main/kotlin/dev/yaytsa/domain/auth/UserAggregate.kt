package dev.yaytsa.domain.auth

import dev.yaytsa.shared.AggregateVersion
import dev.yaytsa.shared.UserId
import java.time.Instant

data class UserAggregate(
    val id: UserId,
    val username: String,
    val passwordHash: String,
    val displayName: String?,
    val email: String?,
    val isAdmin: Boolean,
    val isActive: Boolean,
    val createdAt: Instant,
    val updatedAt: Instant,
    val lastLoginAt: Instant?,
    val apiTokens: List<ApiToken>,
    val version: AggregateVersion,
)

data class ApiToken(
    val id: ApiTokenId,
    val token: String,
    val deviceId: DeviceId,
    val deviceName: String?,
    val createdAt: Instant,
    val lastUsedAt: Instant?,
    val expiresAt: Instant?,
    val revoked: Boolean,
)
