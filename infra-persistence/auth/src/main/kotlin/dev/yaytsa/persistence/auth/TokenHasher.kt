package dev.yaytsa.persistence.auth

import java.security.MessageDigest

object TokenHasher {
    fun hash(token: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest
            .digest(token.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}
