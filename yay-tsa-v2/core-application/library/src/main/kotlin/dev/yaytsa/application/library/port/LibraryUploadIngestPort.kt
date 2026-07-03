package dev.yaytsa.application.library.port

import java.nio.file.Path

sealed interface UploadIngestResult {
    data class Ingested(
        val itemId: String,
        val relativePath: String,
        val duplicate: Boolean,
    ) : UploadIngestResult

    data object LibraryRootUnavailable : UploadIngestResult

    data object NotIngestable : UploadIngestResult
}

interface LibraryUploadIngestPort {
    fun ingest(
        sourceFile: Path,
        originalFilename: String,
    ): UploadIngestResult
}
