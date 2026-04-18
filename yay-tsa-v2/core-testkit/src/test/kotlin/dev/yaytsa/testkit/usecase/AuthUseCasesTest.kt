package dev.yaytsa.testkit.usecase

import dev.yaytsa.application.auth.AuthUseCases
import dev.yaytsa.application.shared.port.DomainNotification
import dev.yaytsa.domain.auth.CreateUser
import dev.yaytsa.shared.AggregateVersion
import dev.yaytsa.shared.CommandContext
import dev.yaytsa.shared.CommandResult
import dev.yaytsa.shared.Failure
import dev.yaytsa.shared.IdempotencyKey
import dev.yaytsa.shared.ProtocolId
import dev.yaytsa.shared.UserId
import dev.yaytsa.testkit.DirectTransactionalExecutor
import dev.yaytsa.testkit.InMemoryIdempotencyStore
import dev.yaytsa.testkit.InMemoryUserRepository
import dev.yaytsa.testkit.RecordingOutbox
import dev.yaytsa.testkit.testCapabilitiesRegistry
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.time.Instant

class AuthUseCasesTest :
    FunSpec({
        val userId = UserId("user-1")
        val now = Instant.parse("2025-01-01T12:00:00Z")

        lateinit var userRepo: InMemoryUserRepository
        lateinit var idempotencyStore: InMemoryIdempotencyStore
        lateinit var outbox: RecordingOutbox
        lateinit var useCases: AuthUseCases

        fun ctx(
            key: String = "key-1",
            protocol: ProtocolId = ProtocolId("JELLYFIN"),
            version: AggregateVersion = AggregateVersion.INITIAL,
        ) = CommandContext(userId, protocol, now, IdempotencyKey(key), version)

        fun createUserCmd() =
            CreateUser(
                userId = userId,
                username = "testuser",
                passwordHash = "hash123",
                displayName = "Test User",
                email = "test@example.com",
                isAdmin = false,
            )

        beforeEach {
            userRepo = InMemoryUserRepository()
            idempotencyStore = InMemoryIdempotencyStore()
            outbox = RecordingOutbox()
            useCases =
                AuthUseCases(
                    userRepo = userRepo,
                    idempotencyStore = idempotencyStore,
                    capabilities = testCapabilitiesRegistry(),
                    txExecutor = DirectTransactionalExecutor(),
                    outbox = outbox,
                )
        }

        test("idempotent replay with same key and payload returns same result without duplicating outbox") {
            val cmd = createUserCmd()

            val first = useCases.execute(cmd, ctx())
            first.shouldBeInstanceOf<CommandResult.Success<*>>()
            outbox.notifications.single().shouldBeInstanceOf<DomainNotification.AuthChanged>()

            val second = useCases.execute(cmd, ctx())
            second.shouldBeInstanceOf<CommandResult.Success<*>>()
            outbox.notifications.single().shouldBeInstanceOf<DomainNotification.AuthChanged>()
        }

        test("idempotent replay with same key but different payload returns InvariantViolation") {
            useCases
                .execute(createUserCmd(), ctx())
                .shouldBeInstanceOf<CommandResult.Success<*>>()

            val differentCmd =
                CreateUser(
                    userId = userId,
                    username = "different-name",
                    passwordHash = "hash123",
                    displayName = "Test User",
                    email = "test@example.com",
                    isAdmin = false,
                )
            val result = useCases.execute(differentCmd, ctx())
            result.shouldBeInstanceOf<CommandResult.Failed>()
            result.failure.shouldBeInstanceOf<Failure.InvariantViolation>()
        }

        test("command via unsupported protocol returns UnsupportedByProtocol") {
            val result = useCases.execute(createUserCmd(), ctx(protocol = ProtocolId("SUBSONIC")))
            result.shouldBeInstanceOf<CommandResult.Failed>()
            result.failure.shouldBeInstanceOf<Failure.UnsupportedByProtocol>()
        }

        test("successful auth command enqueues AuthChanged notification") {
            val result = useCases.execute(createUserCmd(), ctx())
            result.shouldBeInstanceOf<CommandResult.Success<*>>()
            outbox.notifications.single().shouldBeInstanceOf<DomainNotification.AuthChanged>()
        }

        test("failed command does not enqueue outbox notification") {
            // First create the user
            useCases.execute(createUserCmd(), ctx(key = "key-setup"))
            outbox.notifications.clear()

            // Try to create same user again with a different idempotency key
            val result = useCases.execute(createUserCmd(), ctx(key = "key-duplicate"))
            result.shouldBeInstanceOf<CommandResult.Failed>()
            result.failure.shouldBeInstanceOf<Failure.InvariantViolation>()
            outbox.notifications shouldBe emptyList()
        }
    })
