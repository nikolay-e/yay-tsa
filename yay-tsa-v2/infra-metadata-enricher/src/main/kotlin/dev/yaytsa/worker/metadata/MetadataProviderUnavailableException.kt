package dev.yaytsa.worker.metadata

class MetadataProviderUnavailableException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
