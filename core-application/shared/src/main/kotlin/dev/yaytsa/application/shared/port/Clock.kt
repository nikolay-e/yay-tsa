package dev.yaytsa.application.shared.port

import java.time.Instant

interface Clock {
    fun now(): Instant
}
