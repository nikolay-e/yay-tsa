package dev.yaytsa.domain.auth

import dev.yaytsa.shared.AggregateVersion
import dev.yaytsa.shared.CommandContext
import dev.yaytsa.shared.CommandResult
import dev.yaytsa.shared.Failure
import dev.yaytsa.shared.asCommandFailure
import dev.yaytsa.shared.asSuccess

object AuthHandler {
    fun handle(
        snapshot: UserAggregate?,
        cmd: AuthCommand,
        ctx: CommandContext,
    ): CommandResult<UserAggregate> {
        if (cmd is CreateUser) {
            if (snapshot != null) {
                return Failure.InvariantViolation("User already exists: ${cmd.userId.value}").asCommandFailure()
            }
            return createUser(cmd, ctx)
        }

        if (snapshot == null) {
            return Failure.NotFound("User", cmd.userId.value).asCommandFailure()
        }

        val versionCheck = checkVersion(snapshot.version, ctx.expectedVersion)
        if (versionCheck != null) return versionCheck

        if (!snapshot.isActive && cmd !is ActivateUser) {
            return Failure.Unauthorized("User is deactivated").asCommandFailure()
        }

        return when (cmd) {
            is UpdateProfile -> updateProfile(snapshot, cmd, ctx)
            is ChangePassword -> changePassword(snapshot, cmd, ctx)
            is DeactivateUser -> deactivateUser(snapshot, ctx)
            is ActivateUser -> activateUser(snapshot, ctx)
            is RecordLogin -> recordLogin(snapshot, cmd, ctx)
            is CreateApiToken -> createApiToken(snapshot, cmd, ctx)
            is RevokeApiToken -> revokeApiToken(snapshot, cmd, ctx)
            is RecordTokenUsage -> recordTokenUsage(snapshot, cmd, ctx)
            is CreateUser -> error("unreachable")
        }
    }

    private fun updateProfile(
        snapshot: UserAggregate,
        cmd: UpdateProfile,
        ctx: CommandContext,
    ): CommandResult<UserAggregate> {
        cmd.displayName?.let {
            if (it.length > 200) {
                return Failure.InvariantViolation("Display name must not exceed 200 characters").asCommandFailure()
            }
        }
        cmd.email?.let {
            if (it.isBlank() || !it.contains("@")) {
                return Failure.InvariantViolation("Invalid email format").asCommandFailure()
            }
        }
        val newVersion = snapshot.version.next()
        return snapshot
            .copy(
                displayName = cmd.displayName,
                email = cmd.email,
                updatedAt = ctx.requestTime,
                version = newVersion,
            ).asSuccess(newVersion)
    }

    private fun changePassword(
        snapshot: UserAggregate,
        cmd: ChangePassword,
        ctx: CommandContext,
    ): CommandResult<UserAggregate> {
        if (cmd.newPasswordHash.isBlank()) {
            return Failure.InvariantViolation("Password hash cannot be blank").asCommandFailure()
        }
        val newVersion = snapshot.version.next()
        return snapshot
            .copy(
                passwordHash = cmd.newPasswordHash,
                updatedAt = ctx.requestTime,
                version = newVersion,
            ).asSuccess(newVersion)
    }

    private fun deactivateUser(
        snapshot: UserAggregate,
        ctx: CommandContext,
    ): CommandResult<UserAggregate> {
        val newVersion = snapshot.version.next()
        return snapshot
            .copy(
                isActive = false,
                updatedAt = ctx.requestTime,
                version = newVersion,
            ).asSuccess(newVersion)
    }

    private fun activateUser(
        snapshot: UserAggregate,
        ctx: CommandContext,
    ): CommandResult<UserAggregate> {
        val newVersion = snapshot.version.next()
        return snapshot
            .copy(
                isActive = true,
                updatedAt = ctx.requestTime,
                version = newVersion,
            ).asSuccess(newVersion)
    }

    private fun recordLogin(
        snapshot: UserAggregate,
        cmd: RecordLogin,
        ctx: CommandContext,
    ): CommandResult<UserAggregate> {
        val newVersion = snapshot.version.next()
        return snapshot
            .copy(
                lastLoginAt = cmd.loginTime,
                updatedAt = ctx.requestTime,
                version = newVersion,
            ).asSuccess(newVersion)
    }

    private fun createApiToken(
        snapshot: UserAggregate,
        cmd: CreateApiToken,
        ctx: CommandContext,
    ): CommandResult<UserAggregate> {
        // Check for duplicate device_id (only one active token per device)
        val existingForDevice =
            snapshot.apiTokens.find {
                it.deviceId == cmd.deviceId && !it.revoked
            }
        if (existingForDevice != null) {
            return Failure
                .InvariantViolation(
                    "Active token already exists for device ${cmd.deviceId.value}",
                ).asCommandFailure()
        }

        val token =
            ApiToken(
                id = cmd.tokenId,
                token = cmd.token,
                deviceId = cmd.deviceId,
                deviceName = cmd.deviceName,
                createdAt = ctx.requestTime,
                lastUsedAt = null,
                expiresAt = cmd.expiresAt,
                revoked = false,
            )

        val newVersion = snapshot.version.next()
        return snapshot
            .copy(
                apiTokens = snapshot.apiTokens + token,
                updatedAt = ctx.requestTime,
                version = newVersion,
            ).asSuccess(newVersion)
    }

    private fun revokeApiToken(
        snapshot: UserAggregate,
        cmd: RevokeApiToken,
        ctx: CommandContext,
    ): CommandResult<UserAggregate> {
        val tokenIndex = snapshot.apiTokens.indexOfFirst { it.id == cmd.tokenId }
        if (tokenIndex == -1) {
            return Failure.NotFound("ApiToken", cmd.tokenId.value).asCommandFailure()
        }
        val token = snapshot.apiTokens[tokenIndex]
        if (token.revoked) {
            return Failure.InvariantViolation("Token already revoked").asCommandFailure()
        }

        val updatedTokens = snapshot.apiTokens.toMutableList()
        updatedTokens[tokenIndex] = token.copy(revoked = true)

        val newVersion = snapshot.version.next()
        return snapshot
            .copy(
                apiTokens = updatedTokens,
                updatedAt = ctx.requestTime,
                version = newVersion,
            ).asSuccess(newVersion)
    }

    private fun recordTokenUsage(
        snapshot: UserAggregate,
        cmd: RecordTokenUsage,
        ctx: CommandContext,
    ): CommandResult<UserAggregate> {
        val tokenIndex = snapshot.apiTokens.indexOfFirst { it.id == cmd.tokenId }
        if (tokenIndex == -1) {
            return Failure.NotFound("ApiToken", cmd.tokenId.value).asCommandFailure()
        }
        val token = snapshot.apiTokens[tokenIndex]
        if (token.revoked) {
            return Failure.Unauthorized("Token is revoked").asCommandFailure()
        }
        if (token.expiresAt != null && cmd.usedAt >= token.expiresAt) {
            return Failure.Unauthorized("Token has expired").asCommandFailure()
        }

        val updatedTokens = snapshot.apiTokens.toMutableList()
        updatedTokens[tokenIndex] = token.copy(lastUsedAt = cmd.usedAt)

        val newVersion = snapshot.version.next()
        return snapshot
            .copy(
                apiTokens = updatedTokens,
                updatedAt = ctx.requestTime,
                version = newVersion,
            ).asSuccess(newVersion)
    }

    private fun createUser(
        cmd: CreateUser,
        ctx: CommandContext,
    ): CommandResult<UserAggregate> {
        if (cmd.username.isBlank()) {
            return Failure.InvariantViolation("Username cannot be blank").asCommandFailure()
        }
        if (cmd.username.length < 3 || cmd.username.length > 50) {
            return Failure.InvariantViolation("Username must be between 3 and 50 characters").asCommandFailure()
        }
        if (cmd.passwordHash.isBlank()) {
            return Failure.InvariantViolation("Password hash cannot be blank").asCommandFailure()
        }
        val version = AggregateVersion.INITIAL.next()
        return UserAggregate(
            id = cmd.userId,
            username = cmd.username,
            passwordHash = cmd.passwordHash,
            displayName = cmd.displayName,
            email = cmd.email,
            isAdmin = cmd.isAdmin,
            isActive = true,
            createdAt = ctx.requestTime,
            updatedAt = ctx.requestTime,
            lastLoginAt = null,
            apiTokens = emptyList(),
            version = version,
        ).asSuccess(version)
    }

    private fun checkVersion(
        actual: AggregateVersion,
        expected: AggregateVersion,
    ): CommandResult.Failed? {
        if (actual != expected) {
            return CommandResult.Failed(Failure.Conflict(expected, actual))
        }
        return null
    }
}
