package dev.yaytsa.app.integration

import dev.yaytsa.application.shared.port.IdempotencyStore
import dev.yaytsa.shared.IdempotencyKey
import dev.yaytsa.shared.UserId
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.util.UUID
import java.util.concurrent.CompletableFuture

class IdempotencyStoreConflictIntegrationTest : HttpIntegrationTestBase() {
    @Autowired
    lateinit var idempotencyStore: IdempotencyStore

    @Test
    fun `storing the same key twice is a no-op, not a primary-key violation`() {
        val uid = UserId(UUID.randomUUID().toString())
        val key = IdempotencyKey(UUID.randomUUID().toString())

        idempotencyStore.store(uid, "TestCommand", key, "payload-hash-A", 1)
        // The native INSERT ... ON CONFLICT DO NOTHING must swallow the duplicate rather than
        // throw a DataIntegrityViolationException that the executor maps to InvariantViolation.
        assertDoesNotThrow {
            idempotencyStore.store(uid, "TestCommand", key, "payload-hash-A", 1)
        }

        val stored = idempotencyStore.find(uid, "TestCommand", key)
        assertNotNull(stored)
        assertEquals("payload-hash-A", stored!!.payloadHash)
        assertEquals(1L, stored.resultVersion)
    }

    @Test
    fun `on conflict keeps the first stored record and ignores the later payload`() {
        val uid = UserId(UUID.randomUUID().toString())
        val key = IdempotencyKey(UUID.randomUUID().toString())

        idempotencyStore.store(uid, "TestCommand", key, "first-hash", 7)
        idempotencyStore.store(uid, "TestCommand", key, "second-hash", 99)

        val stored = idempotencyStore.find(uid, "TestCommand", key)!!
        assertEquals("first-hash", stored.payloadHash, "DO NOTHING must preserve the first record")
        assertEquals(7L, stored.resultVersion)
    }

    @Test
    fun `concurrent stores of the same key all succeed without error`() {
        val uid = UserId(UUID.randomUUID().toString())
        val key = IdempotencyKey(UUID.randomUUID().toString())

        val futures =
            (1..8).map { v ->
                CompletableFuture.runAsync {
                    idempotencyStore.store(uid, "TestCommand", key, "hash", v.toLong())
                }
            }
        assertDoesNotThrow {
            CompletableFuture.allOf(*futures.toTypedArray()).join()
        }
        assertNotNull(idempotencyStore.find(uid, "TestCommand", key))
    }
}
