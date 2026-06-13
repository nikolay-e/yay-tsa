package dev.yaytsa.shared

import java.security.MessageDigest
import java.util.HexFormat

object Hashing {
    private val hexFormat: HexFormat = HexFormat.of()

    fun sha256Hex(input: String): String = hexEncode(sha256Bytes(input))

    fun sha256Bytes(input: String): ByteArray =
        MessageDigest
            .getInstance("SHA-256")
            .digest(input.toByteArray(Charsets.UTF_8))

    fun hexEncode(bytes: ByteArray): String = hexFormat.formatHex(bytes)

    fun hexDecode(hex: String): ByteArray? =
        try {
            hexFormat.parseHex(hex)
        } catch (_: IllegalArgumentException) {
            null
        }

    fun constantTimeEquals(
        a: String,
        b: String,
    ): Boolean = MessageDigest.isEqual(a.toByteArray(Charsets.UTF_8), b.toByteArray(Charsets.UTF_8))
}
