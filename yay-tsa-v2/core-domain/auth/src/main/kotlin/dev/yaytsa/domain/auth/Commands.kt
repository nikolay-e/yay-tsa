package dev.yaytsa.domain.auth

import dev.yaytsa.shared.Command
import dev.yaytsa.shared.DeviceId
import dev.yaytsa.shared.UserId
import java.time.Instant

sealed interface AuthCommand : Command {
    val userId: UserId
}

sealed interface ExistingUserCommand : AuthCommand

data class UpdateProfile(
    override val userId: UserId,
    val displayName: String?,
    val email: String?,
) : ExistingUserCommand

data class ChangePassword(
    override val userId: UserId,
    val newPasswordHash: String,
) : ExistingUserCommand

data class DeactivateUser(
    override val userId: UserId,
) : ExistingUserCommand

data class ActivateUser(
    override val userId: UserId,
) : ExistingUserCommand

data class RecordLogin(
    override val userId: UserId,
    val loginTime: Instant,
) : ExistingUserCommand

data class CreateApiToken(
    override val userId: UserId,
    val tokenId: ApiTokenId,
    val token: String,
    val deviceId: DeviceId,
    val deviceName: String?,
    val expiresAt: Instant?,
) : ExistingUserCommand

data class RevokeApiToken(
    override val userId: UserId,
    val tokenId: ApiTokenId,
) : ExistingUserCommand

data class RecordTokenUsage(
    override val userId: UserId,
    val tokenId: ApiTokenId,
    val usedAt: Instant,
) : ExistingUserCommand

data class CreateUser(
    override val userId: UserId,
    val username: String,
    val passwordHash: String,
    val displayName: String?,
    val email: String?,
    val isAdmin: Boolean,
) : AuthCommand
