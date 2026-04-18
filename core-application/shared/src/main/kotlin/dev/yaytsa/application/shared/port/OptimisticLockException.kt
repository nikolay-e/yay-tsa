package dev.yaytsa.application.shared.port

class OptimisticLockException(
    message: String,
) : RuntimeException(message)
