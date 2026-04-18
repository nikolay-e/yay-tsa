package dev.yaytsa.persistence.auth

import dev.yaytsa.application.auth.port.UserRepository
import dev.yaytsa.application.shared.port.OptimisticLockException
import dev.yaytsa.domain.auth.ApiToken
import dev.yaytsa.domain.auth.ApiTokenId
import dev.yaytsa.domain.auth.DeviceId
import dev.yaytsa.domain.auth.UserAggregate
import dev.yaytsa.shared.AggregateVersion
import dev.yaytsa.shared.UserId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

class UserRepositoryTest : AbstractPersistenceTest() {
    @Autowired
    lateinit var repository: UserRepository

    @Test
    fun `save and find round-trip with tokens`() {
        val userId = UserId(UUID.randomUUID().toString())
        val now = Instant.now().truncatedTo(ChronoUnit.MILLIS)
        val token = apiToken(now)
        val aggregate = userAggregate(userId, now, listOf(token))

        repository.save(aggregate)

        val found = repository.find(userId)
        assertNotNull(found)
        assertEquals(aggregate.id, found!!.id)
        assertEquals(aggregate.username, found.username)
        assertEquals(aggregate.passwordHash, found.passwordHash)
        assertEquals(aggregate.displayName, found.displayName)
        assertEquals(aggregate.email, found.email)
        assertEquals(aggregate.isAdmin, found.isAdmin)
        assertEquals(aggregate.isActive, found.isActive)
        assertEquals(1, found.apiTokens.size)
        assertEquals(token.id, found.apiTokens[0].id)
        // Token is hashed at rest (SHA-256)
        val expectedHash =
            dev.yaytsa.persistence.auth.TokenHasher
                .hash(token.token)
        assertEquals(expectedHash, found.apiTokens[0].token)
        assertEquals(token.deviceId, found.apiTokens[0].deviceId)
    }

    @Test
    fun `findByUsername returns matching user`() {
        val userId = UserId(UUID.randomUUID().toString())
        val now = Instant.now().truncatedTo(ChronoUnit.MILLIS)
        val username = "testuser_${UUID.randomUUID().toString().take(8)}"
        val aggregate = userAggregate(userId, now, emptyList()).copy(username = username)

        repository.save(aggregate)

        val found = repository.findByUsername(username)
        assertNotNull(found)
        assertEquals(userId, found!!.id)
        assertEquals(username, found.username)
    }

    @Test
    fun `findByApiToken returns user for active non-revoked token`() {
        val userId = UserId(UUID.randomUUID().toString())
        val now = Instant.now().truncatedTo(ChronoUnit.MILLIS)
        val token = apiToken(now)
        val aggregate = userAggregate(userId, now, listOf(token))

        repository.save(aggregate)

        val found = repository.findByApiToken(token.token)
        assertNotNull(found)
        assertEquals(userId, found!!.id)
        assertEquals(1, found.apiTokens.size)
    }

    @Test
    fun `update with OCC increments version`() {
        val userId = UserId(UUID.randomUUID().toString())
        val now = Instant.now().truncatedTo(ChronoUnit.MILLIS)
        val aggregate = userAggregate(userId, now, emptyList())

        repository.save(aggregate)

        val loaded = repository.find(userId)!!
        assertEquals(AggregateVersion.INITIAL, loaded.version)

        val updated =
            loaded.copy(
                displayName = "Updated Name",
                version = loaded.version.next(),
            )
        repository.save(updated)

        val reloaded = repository.find(userId)!!
        assertEquals("Updated Name", reloaded.displayName)
        assertEquals(AggregateVersion(1), reloaded.version)
    }

    @Test
    fun `findByApiToken returns null for revoked token`() {
        val userId = UserId(UUID.randomUUID().toString())
        val now = Instant.now().truncatedTo(ChronoUnit.MILLIS)
        val revokedToken = apiToken(now).copy(revoked = true)
        val aggregate = userAggregate(userId, now, listOf(revokedToken))

        repository.save(aggregate)

        val found = repository.findByApiToken(revokedToken.token)
        assertNull(found)
    }

    @Test
    fun `findByApiToken returns null for non-existent token`() {
        val found = repository.findByApiToken("non-existent-token-value-that-does-not-exist")
        assertNull(found)
    }

    @Test
    fun `save with multiple tokens round-trip preserves all tokens`() {
        val userId = UserId(UUID.randomUUID().toString())
        val now = Instant.now().truncatedTo(ChronoUnit.MILLIS)
        val token1 = apiToken(now).copy(deviceName = "Phone")
        val token2 = apiToken(now).copy(deviceName = "Laptop")
        val token3 = apiToken(now).copy(deviceName = "Tablet")
        val aggregate = userAggregate(userId, now, listOf(token1, token2, token3))

        repository.save(aggregate)

        val found = repository.find(userId)
        assertNotNull(found)
        assertEquals(3, found!!.apiTokens.size)
        val deviceNames = found.apiTokens.map { it.deviceName }.toSet()
        assertEquals(setOf("Phone", "Laptop", "Tablet"), deviceNames)
        // Verify each token's identity is preserved
        val tokenIds = found.apiTokens.map { it.id }.toSet()
        assertEquals(setOf(token1.id, token2.id, token3.id), tokenIds)
    }

    @Test
    fun `concurrent save with stale version is rejected`() {
        val userId = UserId(UUID.randomUUID().toString())
        val now = Instant.now().truncatedTo(ChronoUnit.MILLIS)
        val aggregate = userAggregate(userId, now, emptyList())

        repository.save(aggregate)

        val loaded = repository.find(userId)!!

        // First update succeeds
        val update1 =
            loaded.copy(
                displayName = "First Update",
                version = loaded.version.next(),
            )
        repository.save(update1)

        // Second update with stale version should fail
        val staleUpdate =
            loaded.copy(
                displayName = "Stale Update",
                version = loaded.version.next(),
            )
        assertThrows<OptimisticLockException> {
            repository.save(staleUpdate)
        }
    }

    private fun userAggregate(
        userId: UserId,
        now: Instant,
        tokens: List<ApiToken>,
    ): UserAggregate =
        UserAggregate(
            id = userId,
            username = "user_${userId.value.take(8)}",
            passwordHash = "\$2a\$10\$abcdefghijklmnopqrstuuABCDEFGHIJKLMNOPQRSTUVWXYZ012",
            displayName = "Test User",
            email = "test_${userId.value.take(8)}@example.com",
            isAdmin = false,
            isActive = true,
            createdAt = now,
            updatedAt = now,
            lastLoginAt = null,
            apiTokens = tokens,
            version = AggregateVersion.INITIAL,
        )

    private fun apiToken(now: Instant): ApiToken =
        ApiToken(
            id = ApiTokenId(UUID.randomUUID().toString()),
            token = UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", ""),
            deviceId = DeviceId("device-${UUID.randomUUID().toString().take(8)}"),
            deviceName = "Test Device",
            createdAt = now,
            lastUsedAt = null,
            expiresAt = now.plus(365, ChronoUnit.DAYS),
            revoked = false,
        )
}
