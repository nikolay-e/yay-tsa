package dev.yaytsa.application.shared

import java.security.MessageDigest

/**
 * Computes a deterministic fingerprint of a command payload for idempotency.
 *
 * Uses the fully qualified class name as a namespace prefix, combined with the
 * data class toString() output (which has deterministic field order in Kotlin).
 *
 * Design decision: field renames in command data classes are breaking changes to
 * the API contract and therefore acceptably invalidate existing idempotency records.
 * If a field rename is performed, stale idempotency entries will simply never match
 * (hash mismatch) and the command will execute as a new request.
 *
 * A future migration to kotlinx.serialization would decouple the hash from field
 * names entirely, but is not warranted until the serialization library is adopted
 * across the codebase.
 */
object PayloadFingerprint {
    fun compute(command: Any): String {
        check(command::class.java.declaredFields.none { it.type.isArray }) {
            "Command ${command::class.simpleName} contains array fields — toString() is non-deterministic for arrays"
        }
        val className = command::class.qualifiedName ?: command::class.simpleName ?: "unknown"
        val canonical = "$className:$command"
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(canonical.toByteArray(Charsets.UTF_8))
        return hash.joinToString("") { "%02x".format(it) }
    }
}
