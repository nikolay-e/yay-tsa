package dev.yaytsa.persistence.auth

import dev.yaytsa.shared.Hashing

object TokenHasher {
    fun hash(token: String): String = Hashing.sha256Hex(token)
}
