package dev.yaytsa.domain.auth

import dev.yaytsa.shared.AggregateVersion
import dev.yaytsa.shared.CommandContext
import dev.yaytsa.shared.CommandResult
import dev.yaytsa.shared.Failure
import dev.yaytsa.shared.IdempotencyKey
import dev.yaytsa.shared.ProtocolId
import dev.yaytsa.shared.UserId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.time.Instant

class AuthHandlerTest :
    FunSpec({

        val userId = UserId("user-1")
        val now = Instant.parse("2025-01-01T12:00:00Z")

        fun ctx(version: AggregateVersion = AggregateVersion(1)) =
            CommandContext(
                userId = userId,
                protocolId = ProtocolId("JELLYFIN"),
                requestTime = now,
                idempotencyKey = IdempotencyKey("idem-1"),
                expectedVersion = version,
            )

        fun user(
            version: AggregateVersion = AggregateVersion(1),
            isActive: Boolean = true,
            tokens: List<ApiToken> = emptyList(),
        ) = UserAggregate(
            id = userId,
            username = "testuser",
            passwordHash = "hash123",
            displayName = "Test",
            email = "test@example.com",
            isAdmin = false,
            isActive = isActive,
            createdAt = now,
            updatedAt = now,
            lastLoginAt = null,
            apiTokens = tokens,
            version = version,
        )

        test("UpdateProfile changes display name and email") {
            val result =
                AuthHandler.handle(
                    user(),
                    UpdateProfile(userId, "New Name", "new@example.com"),
                    ctx(),
                )
            result.shouldBeInstanceOf<CommandResult.Success<UserAggregate>>()
            result.value.displayName shouldBe "New Name"
            result.value.email shouldBe "new@example.com"
        }

        test("ChangePassword with blank hash fails") {
            val result =
                AuthHandler.handle(
                    user(),
                    ChangePassword(userId, "  "),
                    ctx(),
                )
            result.shouldBeInstanceOf<CommandResult.Failed>()
            result.failure.shouldBeInstanceOf<Failure.InvariantViolation>()
        }

        test("ChangePassword updates hash") {
            val result =
                AuthHandler.handle(
                    user(),
                    ChangePassword(userId, "newhash"),
                    ctx(),
                )
            result.shouldBeInstanceOf<CommandResult.Success<UserAggregate>>()
            result.value.passwordHash shouldBe "newhash"
        }

        test("DeactivateUser sets isActive to false") {
            val result = AuthHandler.handle(user(), DeactivateUser(userId), ctx())
            result.shouldBeInstanceOf<CommandResult.Success<UserAggregate>>()
            result.value.isActive shouldBe false
        }

        test("deactivated user cannot execute commands") {
            val result =
                AuthHandler.handle(
                    user(isActive = false),
                    UpdateProfile(userId, "X", null),
                    ctx(),
                )
            result.shouldBeInstanceOf<CommandResult.Failed>()
            result.failure.shouldBeInstanceOf<Failure.Unauthorized>()
        }

        test("ActivateUser works on deactivated user") {
            val result =
                AuthHandler.handle(
                    user(isActive = false),
                    ActivateUser(userId),
                    ctx(),
                )
            result.shouldBeInstanceOf<CommandResult.Success<UserAggregate>>()
            result.value.isActive shouldBe true
        }

        test("RecordLogin updates lastLoginAt") {
            val loginTime = Instant.parse("2025-06-01T10:00:00Z")
            val result =
                AuthHandler.handle(
                    user(),
                    RecordLogin(userId, loginTime),
                    ctx(),
                )
            result.shouldBeInstanceOf<CommandResult.Success<UserAggregate>>()
            result.value.lastLoginAt shouldBe loginTime
        }

        test("CreateApiToken adds token") {
            val result =
                AuthHandler.handle(
                    user(),
                    CreateApiToken(
                        userId,
                        ApiTokenId("tok-1"),
                        "secret",
                        DeviceId("dev-1"),
                        "Phone",
                        null,
                    ),
                    ctx(),
                )
            result.shouldBeInstanceOf<CommandResult.Success<UserAggregate>>()
            result.value.apiTokens.size shouldBe 1
            result.value.apiTokens[0].token shouldBe "secret"
        }

        test("CreateApiToken rejects duplicate active device") {
            val existingToken =
                ApiToken(
                    ApiTokenId("tok-1"),
                    "old",
                    DeviceId("dev-1"),
                    "Phone",
                    now,
                    null,
                    null,
                    false,
                )
            val result =
                AuthHandler.handle(
                    user(tokens = listOf(existingToken)),
                    CreateApiToken(
                        userId,
                        ApiTokenId("tok-2"),
                        "new",
                        DeviceId("dev-1"),
                        "Phone",
                        null,
                    ),
                    ctx(),
                )
            result.shouldBeInstanceOf<CommandResult.Failed>()
            result.failure.shouldBeInstanceOf<Failure.InvariantViolation>()
        }

        test("RevokeApiToken marks token as revoked") {
            val token =
                ApiToken(
                    ApiTokenId("tok-1"),
                    "secret",
                    DeviceId("dev-1"),
                    "Phone",
                    now,
                    null,
                    null,
                    false,
                )
            val result =
                AuthHandler.handle(
                    user(tokens = listOf(token)),
                    RevokeApiToken(userId, ApiTokenId("tok-1")),
                    ctx(),
                )
            result.shouldBeInstanceOf<CommandResult.Success<UserAggregate>>()
            result.value.apiTokens[0].revoked shouldBe true
        }

        test("RevokeApiToken for nonexistent token fails") {
            val result =
                AuthHandler.handle(
                    user(),
                    RevokeApiToken(userId, ApiTokenId("nonexistent")),
                    ctx(),
                )
            result.shouldBeInstanceOf<CommandResult.Failed>()
            result.failure.shouldBeInstanceOf<Failure.NotFound>()
        }

        test("RecordTokenUsage fails on expired token") {
            val token =
                ApiToken(
                    ApiTokenId("tok-1"),
                    "secret",
                    DeviceId("dev-1"),
                    null,
                    now,
                    null,
                    now.minusSeconds(1),
                    false,
                )
            val result =
                AuthHandler.handle(
                    user(tokens = listOf(token)),
                    RecordTokenUsage(userId, ApiTokenId("tok-1"), now),
                    ctx(),
                )
            result.shouldBeInstanceOf<CommandResult.Failed>()
            result.failure.shouldBeInstanceOf<Failure.Unauthorized>()
        }

        test("RecordTokenUsage fails on revoked token") {
            val token =
                ApiToken(
                    ApiTokenId("tok-1"),
                    "secret",
                    DeviceId("dev-1"),
                    null,
                    now,
                    null,
                    null,
                    true,
                )
            val result =
                AuthHandler.handle(
                    user(tokens = listOf(token)),
                    RecordTokenUsage(userId, ApiTokenId("tok-1"), now),
                    ctx(),
                )
            result.shouldBeInstanceOf<CommandResult.Failed>()
            result.failure.shouldBeInstanceOf<Failure.Unauthorized>()
        }

        test("CreateUser creates new aggregate when snapshot is null") {
            val result =
                AuthHandler.handle(
                    null,
                    CreateUser(userId, "newuser", "hash", "New User", "new@test.com", false),
                    ctx(version = AggregateVersion(0)),
                )
            result.shouldBeInstanceOf<CommandResult.Success<UserAggregate>>()
            result.value.username shouldBe "newuser"
            result.value.displayName shouldBe "New User"
            result.value.email shouldBe "new@test.com"
            result.value.isActive shouldBe true
            result.value.isAdmin shouldBe false
            result.value.version shouldBe AggregateVersion(1)
        }

        test("CreateUser with blank password hash fails") {
            val cmd = CreateUser(userId, "user1", "", null, null, false)
            val r = AuthHandler.handle(null, cmd, ctx(version = AggregateVersion.INITIAL))
            r.shouldBeInstanceOf<CommandResult.Failed>()
            r.failure.shouldBeInstanceOf<Failure.InvariantViolation>()
        }

        test("CreateUser fails when user already exists") {
            val result =
                AuthHandler.handle(
                    user(),
                    CreateUser(userId, "newuser", "hash", "New User", null, false),
                    ctx(),
                )
            result.shouldBeInstanceOf<CommandResult.Failed>()
            result.failure.shouldBeInstanceOf<Failure.InvariantViolation>()
        }

        test("command on null snapshot returns NotFound") {
            val result =
                AuthHandler.handle(
                    null,
                    UpdateProfile(userId, "X", null),
                    ctx(),
                )
            result.shouldBeInstanceOf<CommandResult.Failed>()
            result.failure.shouldBeInstanceOf<Failure.NotFound>()
        }

        test("version mismatch returns Conflict") {
            val result =
                AuthHandler.handle(
                    user(version = AggregateVersion(5)),
                    UpdateProfile(userId, "X", null),
                    ctx(version = AggregateVersion(3)),
                )
            result.shouldBeInstanceOf<CommandResult.Failed>()
            result.failure.shouldBeInstanceOf<Failure.Conflict>()
        }

        test("CreateApiToken sets createdAt to requestTime") {
            val requestTime = Instant.parse("2025-06-15T08:30:00Z")
            val ctxWithTime = ctx().copy(requestTime = requestTime)
            val result =
                AuthHandler.handle(
                    user(),
                    CreateApiToken(userId, ApiTokenId("tok-1"), "secret", DeviceId("dev-1"), "Phone", null),
                    ctxWithTime,
                )
            result.shouldBeInstanceOf<CommandResult.Success<UserAggregate>>()
            result.value.apiTokens[0].createdAt shouldBe requestTime
        }

        test("UpdateProfile advances updatedAt") {
            val laterTime = Instant.parse("2025-06-01T00:00:00Z")
            val result =
                AuthHandler.handle(
                    user(),
                    UpdateProfile(userId, "New Name", null),
                    ctx().copy(requestTime = laterTime),
                )
            result.shouldBeInstanceOf<CommandResult.Success<UserAggregate>>()
            result.value.updatedAt shouldBe laterTime
        }

        test("ChangePassword advances updatedAt") {
            val laterTime = Instant.parse("2025-06-01T00:00:00Z")
            val result =
                AuthHandler.handle(
                    user(),
                    ChangePassword(userId, "newhash"),
                    ctx().copy(requestTime = laterTime),
                )
            result.shouldBeInstanceOf<CommandResult.Success<UserAggregate>>()
            result.value.updatedAt shouldBe laterTime
        }

        test("DeactivateUser advances updatedAt") {
            val laterTime = Instant.parse("2025-06-01T00:00:00Z")
            val result = AuthHandler.handle(user(), DeactivateUser(userId), ctx().copy(requestTime = laterTime))
            result.shouldBeInstanceOf<CommandResult.Success<UserAggregate>>()
            result.value.updatedAt shouldBe laterTime
        }

        test("RevokeApiToken advances updatedAt") {
            val token = ApiToken(ApiTokenId("tok-1"), "secret", DeviceId("dev-1"), "Phone", now, null, null, false)
            val laterTime = Instant.parse("2025-06-01T00:00:00Z")
            val result =
                AuthHandler.handle(
                    user(tokens = listOf(token)),
                    RevokeApiToken(userId, ApiTokenId("tok-1")),
                    ctx().copy(requestTime = laterTime),
                )
            result.shouldBeInstanceOf<CommandResult.Success<UserAggregate>>()
            result.value.updatedAt shouldBe laterTime
        }

        test("RecordTokenUsage at exact expiresAt fails") {
            val token = ApiToken(ApiTokenId("t1"), "tok", DeviceId("dev"), null, now, null, now, false)
            val result =
                AuthHandler.handle(
                    user(tokens = listOf(token)),
                    RecordTokenUsage(userId, ApiTokenId("t1"), now),
                    ctx(),
                )
            result.shouldBeInstanceOf<CommandResult.Failed>()
            result.failure.shouldBeInstanceOf<Failure.Unauthorized>()
        }

        test("RecordTokenUsage on permanent token succeeds") {
            val token = ApiToken(ApiTokenId("t1"), "tok", DeviceId("dev"), null, now, null, null, false)
            val result =
                AuthHandler.handle(
                    user(tokens = listOf(token)),
                    RecordTokenUsage(userId, ApiTokenId("t1"), now),
                    ctx(),
                )
            result.shouldBeInstanceOf<CommandResult.Success<UserAggregate>>()
        }

        test("RevokeApiToken on already-revoked token fails") {
            val token = ApiToken(ApiTokenId("t1"), "tok", DeviceId("dev"), null, now, null, null, true)
            val result =
                AuthHandler.handle(
                    user(tokens = listOf(token)),
                    RevokeApiToken(userId, ApiTokenId("t1")),
                    ctx(),
                )
            result.shouldBeInstanceOf<CommandResult.Failed>()
            result.failure.shouldBeInstanceOf<Failure.InvariantViolation>()
        }

        test("CreateApiToken on device after revoking previous token succeeds") {
            val revokedToken = ApiToken(ApiTokenId("t1"), "tok1", DeviceId("dev-1"), null, now, null, null, true)
            val result =
                AuthHandler.handle(
                    user(tokens = listOf(revokedToken)),
                    CreateApiToken(userId, ApiTokenId("t2"), "tok2", DeviceId("dev-1"), null, null),
                    ctx(),
                )
            result.shouldBeInstanceOf<CommandResult.Success<UserAggregate>>()
            result.value.apiTokens.size shouldBe 2
        }

        test("RecordLogin on deactivated user fails") {
            val result =
                AuthHandler.handle(
                    user(isActive = false),
                    RecordLogin(userId, now),
                    ctx(),
                )
            result.shouldBeInstanceOf<CommandResult.Failed>()
            result.failure.shouldBeInstanceOf<Failure.Unauthorized>()
        }

        test("Deactivate then activate round-trip preserves state") {
            val activeUser = user()
            val r1 = AuthHandler.handle(activeUser, DeactivateUser(userId), ctx(activeUser.version))
            r1.shouldBeInstanceOf<CommandResult.Success<UserAggregate>>()
            r1.value.isActive shouldBe false

            val r2 = AuthHandler.handle(r1.value, ActivateUser(userId), ctx(r1.value.version))
            r2.shouldBeInstanceOf<CommandResult.Success<UserAggregate>>()
            r2.value.isActive shouldBe true
            r2.value.version shouldBe AggregateVersion(r1.value.version.value + 1)
        }

        test("CreateUser with isAdmin sets flag correctly") {
            val result =
                AuthHandler.handle(
                    null,
                    CreateUser(userId, "admin", "hash123", null, null, true),
                    ctx(version = AggregateVersion.INITIAL),
                )
            result.shouldBeInstanceOf<CommandResult.Success<UserAggregate>>()
            result.value.isAdmin shouldBe true
        }
    })
