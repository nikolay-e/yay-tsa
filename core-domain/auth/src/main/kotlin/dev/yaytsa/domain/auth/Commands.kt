package dev.yaytsa.domain.auth

import dev.yaytsa.shared.Command
import dev.yaytsa.shared.UserId
import java.time.Instant

sealed interface AuthCommand : Command {
    val userId: UserId
}

data class UpdateProfile(
    override val userId: UserId,
    val displayName: String?,
    val email: String?,
) : AuthCommand

data class ChangePassword(
    override val userId: UserId,
    val newPasswordHash: String,
) : AuthCommand

data class DeactivateUser(
    override val userId: UserId,
) : AuthCommand

data class ActivateUser(
    override val userId: UserId,
) : AuthCommand

data class RecordLogin(
    override val userId: UserId,
    val loginTime: Instant,
) : AuthCommand

data class CreateApiToken(
    override val userId: UserId,
    val tokenId: ApiTokenId,
    val token: String,
    val deviceId: DeviceId,
    val deviceName: String?,
    val expiresAt: Instant?,
) : AuthCommand

data class RevokeApiToken(
    override val userId: UserId,
    val tokenId: ApiTokenId,
) : AuthCommand

data class RecordTokenUsage(
    override val userId: UserId,
    val tokenId: ApiTokenId,
    val usedAt: Instant,
) : AuthCommand

data class CreateUser(
    override val userId: UserId,
    val username: String,
    val passwordHash: String,
    val displayName: String?,
    val email: String?,
    val isAdmin: Boolean,
) : AuthCommand
