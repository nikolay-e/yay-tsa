package dev.yaytsa.application.shared

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

class PayloadFingerprintTest :
    FunSpec({
        test("same command produces same fingerprint") {
            data class TestCmd(
                val a: String,
                val b: Int,
            )
            val hash1 = PayloadFingerprint.compute(TestCmd("x", 1))
            val hash2 = PayloadFingerprint.compute(TestCmd("x", 1))
            hash1 shouldBe hash2
        }

        test("different values produce different fingerprint") {
            data class TestCmd(
                val a: String,
                val b: Int,
            )
            val hash1 = PayloadFingerprint.compute(TestCmd("x", 1))
            val hash2 = PayloadFingerprint.compute(TestCmd("y", 1))
            hash1 shouldNotBe hash2
        }

        test("fingerprint is 64-char hex SHA-256") {
            data class TestCmd(
                val value: String,
            )
            val hash = PayloadFingerprint.compute(TestCmd("test"))
            hash.length shouldBe 64
            hash.all { it in '0'..'9' || it in 'a'..'f' } shouldBe true
        }

        test("class name is included in fingerprint") {
            data class CmdA(
                val x: String,
            )

            data class CmdB(
                val x: String,
            )
            val hashA = PayloadFingerprint.compute(CmdA("same"))
            val hashB = PayloadFingerprint.compute(CmdB("same"))
            hashA shouldNotBe hashB
        }
    })
